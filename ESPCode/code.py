import time
import wifi
import socketpool
import board
import pwmio
import microcontroller
import json
import mdns
import asyncio
import digitalio
import gc
from adafruit_motor import motor

# ==============================================================================
#   WIRING CONFIGURATION (L9110S Blue Board -> ESP32-S3)
# ==============================================================================
WIFI_CONFIG_FILE = "/wifi_config.json"

try:
    PIN_A = board.IO6
    PIN_B = board.IO5
except AttributeError:
    PIN_A = board.GPIO6
    PIN_B = board.GPIO5

def get_css():
    return """
    <style>
        :root { --primary: #0ea5e9; --bg: #0f172a; --card: #1e293b; --text: #f1f5f9; }
        body { font-family: -apple-system, sans-serif; background: var(--bg); color: var(--text); padding: 15px; display: flex; flex-direction: column; align-items: center; min-height: 100vh; margin:0;}
        .container { width: 100%; max-width: 420px; }
        .card { background: var(--card); padding: 25px; border-radius: 20px; border: 1px solid #334155; text-align: center; margin-top: 15px; }
        h2 { color: var(--primary); margin-top: 0; }
        input, select { width: 100%; padding: 12px; margin: 8px 0 20px; border-radius: 10px; border: 1px solid #475569; background: #0f172a; color: white; box-sizing: border-box; }
        button { width: 100%; padding: 15px; border: none; border-radius: 12px; font-weight: bold; cursor: pointer; color: white; margin-top:10px; transition: transform 0.1s; }
        button:active { transform: scale(0.96); opacity: 0.9; }
        .btn-green { background: #10b981; }
        .btn-blue { background: #3b82f6; }
        .btn-red { background: #ef4444; }
        .btn-gray { background: #475569; margin-top: 5px; }
        .btn-outline { background: transparent; border: 2px solid #3b82f6; color: #3b82f6; margin-top: 15px; }
        .status-bar { padding: 12px; border-radius: 10px; font-weight: bold; text-align: center; font-size: 0.85rem; border: 1px solid; text-transform: uppercase; }
        .writable { background: #064e3b; color: #34d399; border-color: #10b981; }
        .readonly { background: #451a03; color: #fbbf24; border-color: #f59e0b; }
        .pc-mode { background: #172554; color: #93c5fd; border-color: #3b82f6; }
        .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 15px; }
        #toast { visibility: hidden; min-width: 250px; background-color: #333; color: #fff; text-align: center; border-radius: 50px; padding: 12px 20px; position: fixed; z-index: 99; bottom: 30px; left: 50%; transform: translateX(-50%); opacity: 0; transition: opacity 0.3s, bottom 0.3s; }
        #toast.show { visibility: visible; opacity: 1; bottom: 50px; }
        #toast.error { background-color: #ef4444; }
        
        input[type=range] { -webkit-appearance: none; background: transparent; }
        input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; height: 24px; width: 24px; border-radius: 50%; background: var(--primary); margin-top: -10px; box-shadow: 0 0 10px rgba(14,165,233,0.5); }
        input[type=range]::-webkit-slider-runnable-track { width: 100%; height: 4px; background: #475569; border-radius: 2px; }
    </style>
    """

def get_setup_html(is_write_mode):
    stat_class = "writable" if is_write_mode else "readonly"
    stat_text = "MODE: WRITABLE (Ready to Save)" if is_write_mode else "MODE: READ-ONLY (PC Edit Mode)"
    sub_text = "" if is_write_mode else "<p style='font-size:0.75rem;color:#cbd5e1;margin:5px 0 0;text-transform:none'>To save WiFi: Press BOOT button on ESP32, then refresh.</p>"
    
    return f"""
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>ESP Setup</title><meta name="viewport" content="width=device-width, initial-scale=1">{get_css()}</head>
    <body>
        <div class="container">
            <div class="status-bar {stat_class}">{stat_text}{sub_text}</div>
            <div class="card">
                <h2>WiFi Setup</h2>
                <div id="status-msg" style="font-size:0.8rem;color:#64748b;margin-bottom:5px">Initializing...</div>
                <select id="ssid"><option value="">-- Waiting --</option></select>
                <button class="btn-blue" onclick="loadResults()">Reload List</button>
                <input type="text" id="pass" placeholder="Password" style="margin-top:20px">
                <button class="btn-green" onclick="save()">Save & Reboot</button>
            </div>
        </div>
        <script>
        window.onload=function(){{setTimeout(loadResults,1000)}};
        function loadResults(){{
            const s=document.getElementById('ssid'),m=document.getElementById('status-msg');
            m.innerText="Scanning..."; s.disabled=true;
            fetch('/api/results?t='+Date.now()).then(r=>r.json()).then(d=>{{
                s.innerHTML=''; s.disabled=false; m.innerText="Found "+d.length+" networks.";
                if(d.length===0) s.innerHTML='<option value="">No Networks</option>';
                else {{
                    s.innerHTML='<option value="">-- Select --</option>';
                    d.forEach(n=>{{let o=document.createElement('option');o.value=n.ssid;o.innerText=n.ssid+' ('+n.rssi+')';s.appendChild(o)}});
                }}
            }}).catch(()=>{{\setTimeout(loadResults,3000)}});
        }}
        function save(){{
            const s=document.getElementById('ssid').value,p=document.getElementById('pass').value;
            if(!s||!p)return alert('Missing Info');
            document.querySelector('.btn-green').innerText="Saving...";
            fetch('/api/save?ssid='+encodeURIComponent(s)+'&pass='+encodeURIComponent(p))
            .then(r=>r.text()).then(t=>{{alert(t);if(t.includes('Saved'))location.reload()}})
            .catch(()=>alert("Error"));
        }}
        </script></body></html>"""

def get_app_html():
    return f"""
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>Fan Control</title><meta name="viewport" content="width=device-width, initial-scale=1">{get_css()}
    <script>
        function showToast(msg, isErr) {{
            let x = document.getElementById("toast"); 
            x.innerText = msg; 
            x.className = "show" + (isErr ? " error" : ""); 
            setTimeout(() => x.className = "", 3000);
        }}

        function updateDisplay(t, mode) {{
            let direction = "Stopped";
            if(t > 0) direction = "Spinning " + Math.round(t) + "%";
            if(t < 0) direction = "Reverse " + Math.round(Math.abs(t)) + "%";
            if(mode === "loop") direction = "Breeze Mode";
            document.getElementById('disp').innerText = direction;
        }}

        function updateThrottle(val) {{
            updateDisplay(val, "manual");
            fetch('/api/throttle?val=' + val).catch(() => showToast("Connection Error!", true));
        }}

        function sendCmd(b, u, d_text, d_val) {{
            const t = b.innerText; 
            b.style.opacity = "0.7"; 
            fetch(u).then(() => {{
                b.style.opacity = "1"; 
                if(d_text) document.getElementById('disp').innerText = d_text;
                if(d_val !== undefined) document.getElementById('slider').value = d_val;
                showToast("Set: " + t, false);
            }}).catch(() => {{
                b.style.opacity = "1";
                showToast("Connection Error!", true);
            }});
        }}

        function refreshStatus() {{
            let btn = document.getElementById("btn-refresh");
            btn.innerText = "Refreshing...";
            fetch('/api/status?t=' + Date.now()).then(r => r.json()).then(d => {{
                let t = d.throttle * 100;
                document.getElementById('slider').value = t;
                updateDisplay(t, d.mode);
                btn.innerText = "Refresh Status";
            }}).catch(() => {{
                btn.innerText = "Refresh Status";
                showToast("Error Refreshing", true);
            }});
        }}

        window.onload = refreshStatus;
    </script>
    </head>
    <body>
        <div id="toast">Command Sent</div>
        <div class="container">
            <div class="status-bar pc-mode">ONLINE: FAN CONTROL</div>
            <div class="card">
                <h2>DC Motor / Fan</h2>
                
                <h2 id="disp" style="font-size:1.8rem;margin:15px 0;color:white;text-shadow:0 0 10px #0ea5e9">Loading...</h2>
                
                <div style="display:flex; justify-content:space-between; color:#94a3b8; font-size:0.8rem; margin-bottom:5px;">
                    <span>REV</span><span>STOP</span><span>FWD</span>
                </div>
                <input type="range" id="slider" min="-100" max="100" value="0" step="5" style="width:100%" oninput="updateThrottle(this.value)" onchange="updateThrottle(this.value)">
                
                <div class="grid-3" style="margin-top:20px;">
                    <button class="btn-gray" onclick="sendCmd(this,'/api/throttle?val=-100','Reverse Max', -100)">Rev Max</button>
                    <button class="btn-red" onclick="sendCmd(this,'/api/stop','Stopped', 0)">STOP</button>
                    <button class="btn-gray" onclick="sendCmd(this,'/api/throttle?val=100','Spinning Max', 100)">Fwd Max</button>
                </div>

                <button id="btn-refresh" class="btn-outline" onclick="refreshStatus()">Refresh Status</button>
                
                <hr style="border-color:#334155; margin: 20px 0;">
                
                <label style="color:#94a3b8;font-size:0.9rem">Automation</label>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:15px;margin-top:5px">
                    <button class="btn-blue" onclick="sendCmd(this,'/api/loop','Breeze Mode')">Breeze</button>
                    <button class="btn-red" onclick="sendCmd(this,'/api/stop','Stopped', 0)">Stop All</button>
                </div>
            </div>
        </div>
    </body></html>"""

# --- 2. GLOBAL STATE ---
state = {
    "target_throttle": 0.0,
    "current_throttle": 0.0,
    "mode": "manual",
    "loop_dir": 1,
    "web_mode": "SETUP" 
}
scan_cache = []

# --- 3. HARDWARE INIT ---
try: nvm_val = microcontroller.nvm[0]
except: nvm_val = 0
is_write_mode = (nvm_val == 1)

try: BTN_PIN = board.IO0
except: BTN_PIN = board.GPIO0
boot_btn = digitalio.DigitalInOut(BTN_PIN)
boot_btn.direction = digitalio.Direction.INPUT
boot_btn.pull = digitalio.Pull.UP

# --- 4. WIFI CONNECTION ---
print("[INIT] Starting System...")

def load_config():
    try:
        with open(WIFI_CONFIG_FILE, "r") as f: return json.load(f)
    except: return None

config = load_config()
wifi_connected = False

if config:
    for i in range(3):
        try:
            print(f"[WIFI] Connecting... (Attempt {i+1})")
            wifi.radio.connect(config['ssid'], config['password'])
            state["web_mode"] = "APP"
            wifi_connected = True
            print(f"[WIFI] Connected! IP: {wifi.radio.ipv4_address}")
            break
        except Exception as e:
            time.sleep(1.5)

if not wifi_connected:
    state["web_mode"] = "SETUP"
    try:
        found = set()
        scan_cache = []
        for n in wifi.radio.start_scanning_networks():
            s = n.ssid
            if s and s not in found:
                found.add(s)
                scan_cache.append({'ssid': s, 'rssi': n.rssi})
        wifi.radio.stop_scanning_networks()
        scan_cache.sort(key=lambda x: x['rssi'], reverse=True)
        scan_cache = scan_cache[:10]
        wifi.radio.start_ap("ESP32-Setup", "12345678", channel=1)
    except Exception as e:
        print(e)

try:
    mdns_server = mdns.Server(wifi.radio)
    mdns_server.hostname = "esp"
    mdns_server.advertise_service(service_type="_http", protocol="_tcp", port=80)
except: pass

# --- 5. HARDWARE INIT (PART 2) ---
dc_motor = None
try:
    pwm_a = pwmio.PWMOut(PIN_A, frequency=100)
    pwm_b = pwmio.PWMOut(PIN_B, frequency=100)
    dc_motor = motor.DCMotor(pwm_a, pwm_b)
    dc_motor.decay_mode = motor.FAST_DECAY 
    dc_motor.throttle = 0
except Exception as e:
    print(f"[INIT] Motor Init Failed: {e}")

# --- 6. HELPERS ---
def save_config(ssid, password):
    if not is_write_mode: return False
    try:
        with open(WIFI_CONFIG_FILE, "w") as f:
            json.dump({"ssid": ssid, "password": password}, f)
        return True
    except: return False

# ASYNC SAFE CHUNKED SENDER (Fixes the HTML Truncation bug seen in the video)
async def send_chunked(client, data):
    view = memoryview(data)
    offset = 0
    total = len(data)
    chunk_size = 512
    while offset < total:
        try:
            sent = client.send(view[offset:offset+chunk_size])
            if sent:
                offset += sent
        except OSError as e:
            if e.errno == 11: # EAGAIN - Socket buffer is full, wait for it to clear
                await asyncio.sleep(0.05)
                continue
            else:
                break # Hard connection error
        await asyncio.sleep(0.001) # Yield to event loop

# --- 7. SERVER HANDLER ---
async def handle_request(client):
    try:
        client.setblocking(False)
        buf = bytearray(1024)
        req_txt = ""
        got_data = False
        
        for _ in range(20):
            try:
                n = client.recv_into(buf)
                if n > 0:
                    req_txt = buf[:n].decode('utf-8')
                    got_data = True
                    break
            except OSError as e:
                if e.errno == 11: 
                    await asyncio.sleep(0.05)
                    continue
                return
        
        if not got_data: return
        line = req_txt.split("\r\n")[0]
        try: method, path, _ = line.split(" ")
        except: return

        if "/api/results" in path:
            gc.collect()
            try:
                js = json.dumps(scan_cache)
                client.send(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n")
                client.send(js.encode("utf-8"))
            except: client.send(b"HTTP/1.1 200 OK\r\n\r\n[]")
            return

        resp = ""
        ctype = "text/html; charset=utf-8"

        if path == "/":
            if state["web_mode"] == "SETUP": resp = get_setup_html(is_write_mode)
            else: resp = get_app_html()
            
        elif "/api/status" in path:
            resp = json.dumps({"throttle": state["current_throttle"], "mode": state["mode"]})
            ctype = "application/json"
        
        elif "/api/save" in path:
            q = path.split("?")[1] if "?" in path else ""
            s_val, p_val = "", ""
            for p in q.split("&"):
                if "ssid=" in p: s_val = p.split("=")[1].replace("%20"," ").replace("+"," ")
                if "pass=" in p: p_val = p.split("=")[1].replace("%20"," ").replace("+"," ")
            
            if save_config(s_val, p_val):
                resp = "Saved! Resetting..."
                microcontroller.nvm[0] = 0
                client.send(f"HTTP/1.1 200 OK\r\n\r\n{resp}".encode())
                client.close()
                time.sleep(1)
                microcontroller.reset()
            else:
                resp = "Error: READ-ONLY. Press BOOT button on device."
                ctype = "text/plain"

        elif "/api/throttle" in path:
            try:
                val = path.split("val=")[1].split("&")[0]
                f_val = float(val) / 100.0
                if f_val > 1.0: f_val = 1.0
                if f_val < -1.0: f_val = -1.0
                state["mode"] = "manual"
                state["target_throttle"] = f_val
            except: pass
            resp = '{"status":"ok"}'
            ctype = "application/json"

        elif "/api/loop" in path:
            state["mode"] = "loop"
            resp = '{"status":"ok"}'
            ctype = "application/json"
            
        elif "/api/stop" in path:
            state["mode"] = "manual"
            state["target_throttle"] = 0.0
            resp = '{"status":"ok"}'
            ctype = "application/json"

        resp_bytes = resp.encode('utf-8')
        header = f"HTTP/1.1 200 OK\r\nContent-Type: {ctype}\r\nContent-Length: {len(resp_bytes)}\r\nConnection: close\r\n\r\n"
        client.send(header.encode('utf-8'))
        
        # FIX: Replaced synchronous sender with asynchronous chunk sender
        await send_chunked(client, resp_bytes)
        await asyncio.sleep(0.05)

    except Exception as e:
        pass
    finally:
        try: client.close()
        except: pass
        gc.collect()

# --- 8. ASYNC LOOPS ---
async def server_task():
    pool = socketpool.SocketPool(wifi.radio)
    s = pool.socket(pool.AF_INET, pool.SOCK_STREAM)
    s.setblocking(False)
    s.setsockopt(pool.SOL_SOCKET, pool.SO_REUSEADDR, 1)
    s.bind(('0.0.0.0', 80))
    s.listen(2)
    while True:
        try:
            c, a = s.accept()
            await handle_request(c)
        except OSError:
            await asyncio.sleep(0.01)

async def motor_task():
    last_heartbeat = 0
    step_size = 0.05 
    
    while True:
        if not dc_motor: 
            await asyncio.sleep(1)
            continue
        
        now = time.monotonic()
        if now - last_heartbeat > 2.0: last_heartbeat = now

        if state["mode"] == "loop":
            nxt = state["current_throttle"] + (state["loop_dir"] * step_size)
            if nxt >= 1.0: 
                state["loop_dir"] = -1
                nxt = 1.0
            elif nxt <= 0.3: 
                state["loop_dir"] = 1
                nxt = 0.3
            state["current_throttle"] = nxt
            state["target_throttle"] = nxt
            dc_motor.throttle = state["current_throttle"]
            await asyncio.sleep(0.15) 
            
        elif state["mode"] == "manual":
            diff = state["target_throttle"] - state["current_throttle"]
            if abs(diff) < step_size:
                if state["current_throttle"] != state["target_throttle"]:
                    state["current_throttle"] = state["target_throttle"]
                    dc_motor.throttle = state["target_throttle"]
                await asyncio.sleep(0.05)
            else:
                if diff > 0: state["current_throttle"] += step_size
                else: state["current_throttle"] -= step_size
                dc_motor.throttle = state["current_throttle"]
                await asyncio.sleep(0.05) 

async def button_task():
    while True:
        if not boot_btn.value:
            cur = 0
            try: cur = microcontroller.nvm[0]
            except: pass
            new_mode = 1 if cur == 0 else 0
            microcontroller.nvm[0] = new_mode
            await asyncio.sleep(1.0)
            microcontroller.reset()
        await asyncio.sleep(0.1)

async def main():
    await asyncio.gather(server_task(), motor_task(), button_task())

try: asyncio.run(main())
except KeyboardInterrupt: pass