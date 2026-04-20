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
#   WIRING CONFIGURATION (L9110S -> ESP32-S3-ZERO)
# ==============================================================================
#   PIN_A (GP6 silkscreen) -> board.IO6
#   PIN_B (GP5 silkscreen) -> board.IO5
#   BOOT Button            -> board.BUTTON (GPIO 0)
# ==============================================================================

# --- 1. CONFIGURATION ---
WIFI_CONFIG_FILE = "/wifi_config.json"

# Pins for Waveshare ESP32-S3-Zero (using the IO naming convention)
PIN_A = board.IO6
PIN_B = board.IO5

# Onboard Boot Button (GPIO 0)
try:
    BTN_PIN = board.BUTTON 
except AttributeError:
    BTN_PIN = board.IO0

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
        .status-bar { padding: 12px; border-radius: 10px; font-weight: bold; text-align: center; font-size: 0.85rem; border: 1px solid; text-transform: uppercase; }
        .writable { background: #064e3b; color: #34d399; border-color: #10b981; }
        .readonly { background: #451a03; color: #fbbf24; border-color: #f59e0b; }
        .pc-mode { background: #172554; color: #93c5fd; border-color: #3b82f6; }
        .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 15px; }
        input[type=range] { -webkit-appearance: none; background: transparent; width: 100%; }
        input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; height: 24px; width: 24px; border-radius: 50%; background: var(--primary); margin-top: -10px; }
        input[type=range]::-webkit-slider-runnable-track { width: 100%; height: 4px; background: #475569; border-radius: 2px; }
    </style>
    """

def get_setup_html(is_write_mode):
    stat_class = "writable" if is_write_mode else "readonly"
    stat_text = "MODE: WRITABLE (Ready to Save)" if is_write_mode else "MODE: READ-ONLY (PC Mode)"
    sub_text = "" if is_write_mode else "<p style='font-size:0.75rem;margin:5px 0 0;'>Press BOOT button on board to unlock saving.</p>"
    
    return f"""
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>ESP Setup</title><meta name="viewport" content="width=device-width, initial-scale=1">{get_css()}</head>
    <body>
        <div class="container">
            <div class="status-bar {stat_class}">{stat_text}{sub_text}</div>
            <div class="card">
                <h2>WiFi Setup</h2>
                <div id="status-msg" style="font-size:0.8rem;color:#64748b;margin-bottom:5px">Scanning...</div>
                <select id="ssid"><option value="">-- Loading --</option></select>
                <input type="password" id="pass" placeholder="Password" style="margin-top:20px">
                <button class="btn-green" onclick="save()">Save & Reboot</button>
            </div>
        </div>
        <script>
        window.onload=function(){{
            fetch('/api/results').then(r=>r.json()).then(d=>{{
                const s=document.getElementById('ssid'); s.innerHTML='<option value="">-- Select --</option>';
                d.forEach(n=>{{let o=document.createElement('option');o.value=n.ssid;o.innerText=n.ssid+' ('+n.rssi+')';s.appendChild(o)}});
                document.getElementById('status-msg').innerText="Networks Found";
            }}).catch(()=>{{ document.getElementById('status-msg').innerText="Scan Error"; }});
        }};
        function save(){{
            const s=document.getElementById('ssid').value, p=document.getElementById('pass').value;
            if(!s) return alert('Select SSID');
            fetch('/api/save?ssid='+encodeURIComponent(s)+'&pass='+encodeURIComponent(p))
            .then(r=>r.text()).then(t=>{{ alert(t); if(t.includes('Saved')) location.reload(); }});
        }}
        </script></body></html>"""

def get_app_html():
    return f"""
    <!DOCTYPE html><html><head><meta charset="utf-8"><title>Fan Control</title><meta name="viewport" content="width=device-width, initial-scale=1">{get_css()}
    <script>
        function updateThrottle(val) {{
            let d = val == 0 ? "Stopped" : (val > 0 ? "Forward " + val + "%" : "Reverse " + Math.abs(val) + "%");
            document.getElementById('disp').innerText = d;
            fetch('/api/throttle?val=' + val);
        }}
        function sendCmd(u, d_text, d_val) {{
            fetch(u).then(() => {{
                if(d_text) document.getElementById('disp').innerText = d_text;
                if(d_val !== undefined) document.getElementById('slider').value = d_val;
            }});
        }}
    </script>
    </head>
    <body>
        <div class="container">
            <div class="status-bar pc-mode">FAN CONTROL ONLINE</div>
            <div class="card">
                <h2 id="disp" style="font-size:1.8rem;margin:15px 0;color:white;text-shadow:0 0 10px #0ea5e9">Stopped</h2>
                <input type="range" id="slider" min="-100" max="100" value="0" step="5" oninput="updateThrottle(this.value)">
                <div class="grid-3" style="margin-top:20px;">
                    <button class="btn-gray" onclick="sendCmd('/api/throttle?val=-100','Reverse Max', -100)">Rev</button>
                    <button class="btn-red" onclick="sendCmd('/api/stop','Stopped', 0)">STOP</button>
                    <button class="btn-gray" onclick="sendCmd('/api/throttle?val=100','Forward Max', 100)">Fwd</button>
                </div>
                <button class="btn-blue" onclick="sendCmd('/api/loop','Breeze Mode')">Breeze Mode</button>
            </div>
        </div>
    </body></html>"""

# --- 2. GLOBAL STATE ---
state = {"target_throttle": 0.0, "current_throttle": 0.0, "mode": "manual", "loop_dir": 1, "web_mode": "SETUP"}
scan_cache = []

# --- 3. HARDWARE INIT ---
print("\n" + "="*30)
print("SYSTEM BOOTING")
print("="*30)

try: nvm_val = microcontroller.nvm[0]
except: nvm_val = 0
is_write_mode = (nvm_val == 1)
print(f"[STORAGE] Mode: {'Writable' if is_write_mode else 'Read-Only'}")

boot_btn = digitalio.DigitalInOut(BTN_PIN)
boot_btn.direction = digitalio.Direction.INPUT
boot_btn.pull = digitalio.Pull.UP

try:
    pwm_a = pwmio.PWMOut(PIN_A, frequency=1000)
    pwm_b = pwmio.PWMOut(PIN_B, frequency=1000)
    dc_motor = motor.DCMotor(pwm_a, pwm_b)
    dc_motor.throttle = 0
    print(f"[MOTOR] Initialized on IO6 and IO5")
except Exception as e:
    dc_motor = None
    print(f"[ERROR] Motor Setup: {e}")

# --- 4. WIFI LOGIC ---
wifi.radio.enabled = True

def load_config():
    try:
        with open(WIFI_CONFIG_FILE, "r") as f: return json.load(f)
    except: return None

config = load_config()
wifi_connected = False

if config:
    print(f"[WIFI] Connecting to {config['ssid']}...")
    try:
        wifi.radio.connect(config['ssid'], config['password'], timeout=10)
        state["web_mode"] = "APP"
        wifi_connected = True
        print(f"[WIFI] Connected! IP: {wifi.radio.ipv4_address}")
    except Exception as e:
        print(f"[WIFI] Connection failed: {e}")

if not wifi_connected:
    state["web_mode"] = "SETUP"
    try:
        print("[WIFI] Scanning...")
        networks = wifi.radio.start_scanning_networks()
        for n in networks:
            if n.ssid: scan_cache.append({'ssid': n.ssid, 'rssi': n.rssi})
        wifi.radio.stop_scanning_networks()
        scan_cache.sort(key=lambda x: x['rssi'], reverse=True)
        wifi.radio.start_ap("ESP32-S3-Setup", "12345678")
        print("[WIFI] AP Started: 'ESP32-S3-Setup' at http://192.168.4.1")
    except Exception as e:
        print(f"[ERROR] WiFi AP: {e}")

try:
    server_mdns = mdns.Server(wifi.radio)
    server_mdns.hostname = "esp"
    server_mdns.advertise_service(service_type="_http", protocol="_tcp", port=80)
    print("[WIFI] mDNS: http://esp.local")
except: pass

# --- 5. SERVER ---
async def handle_request(client):
    try:
        buf = bytearray(1024)
        n = client.recv_into(buf)
        req = buf[:n].decode('utf-8')
        path = req.split(" ")[1] if " " in req else "/"
        
        if "/api/results" in path:
            js = json.dumps(scan_cache[:10])
            client.send(f"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{js}".encode())
        elif "/api/save" in path:
            if is_write_mode:
                q = path.split("?")[1]
                s, p = "", ""
                for pair in q.split("&"):
                    if "ssid=" in pair: s = pair.split("=")[1].replace("%20"," ").replace("+"," ")
                    if "pass=" in pair: p = pair.split("=")[1].replace("%20"," ").replace("+"," ")
                with open(WIFI_CONFIG_FILE, "w") as f: json.dump({"ssid": s, "password": p}, f)
                microcontroller.nvm[0] = 0
                client.send(b"HTTP/1.1 200 OK\r\n\r\nSaved! Rebooting...")
                await asyncio.sleep(1)
                microcontroller.reset()
            else:
                client.send(b"HTTP/1.1 200 OK\r\n\r\nError: Device Read-Only. Press Boot Button.")
        elif "/api/throttle" in path:
            state["mode"] = "manual"
            state["target_throttle"] = float(path.split("val=")[1].split("&")[0]) / 100.0
            client.send(b"HTTP/1.1 200 OK\r\n\r\nOK")
        elif "/api/loop" in path:
            state["mode"] = "loop"
            client.send(b"HTTP/1.1 200 OK\r\n\r\nOK")
        elif "/api/stop" in path:
            state["mode"] = "manual"
            state["target_throttle"] = 0.0
            client.send(b"HTTP/1.1 200 OK\r\n\r\nOK")
        else:
            html = get_setup_html(is_write_mode) if state["web_mode"] == "SETUP" else get_app_html()
            client.send(f"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n{html}".encode())
    except: pass
    finally: client.close()

async def server_task():
    pool = socketpool.SocketPool(wifi.radio)
    s = pool.socket(pool.AF_INET, pool.SOCK_STREAM)
    s.bind(('0.0.0.0', 80))
    s.listen(2)
    s.setblocking(False)
    while True:
        try:
            c, a = s.accept()
            await handle_request(c)
        except OSError: await asyncio.sleep(0.05)

async def motor_task():
    step = 0.05
    while True:
        if not dc_motor: await asyncio.sleep(1); continue
        if state["mode"] == "loop":
            nxt = state["current_throttle"] + (state["loop_dir"] * step)
            if nxt >= 1.0 or nxt <= 0.3: state["loop_dir"] *= -1
            state["current_throttle"] = max(0.3, min(1.0, nxt))
        else:
            diff = state["target_throttle"] - state["current_throttle"]
            if abs(diff) < step: state["current_throttle"] = state["target_throttle"]
            else: state["current_throttle"] += step if diff > 0 else -step
        dc_motor.throttle = state["current_throttle"]
        await asyncio.sleep(0.1)

async def button_task():
    while True:
        if not boot_btn.value:
            microcontroller.nvm[0] = 1 if nvm_val == 0 else 0
            print("[SYSTEM] Button Pressed. Rebooting...")
            await asyncio.sleep(1)
            microcontroller.reset()
        await asyncio.sleep(0.1)

async def main():
    await asyncio.gather(server_task(), motor_task(), button_task())

try:
    asyncio.run(main())
except KeyboardInterrupt:
    pass