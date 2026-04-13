# boot.py - Handles Read/Write permissions
import storage, microcontroller, board, digitalio

# Check NVM flag: 0=PC_Edit, 1=ESP_Write
try:
    mode = microcontroller.nvm[0]
except:
    mode = 0

# If Mode is 1, ESP32 takes write control (to save WiFi)
if mode == 1:
    storage.remount("/", False)
else:
    storage.remount("/", True)