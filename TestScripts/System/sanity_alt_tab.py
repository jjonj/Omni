import ctypes
import time

# Windows Constants
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_SCANCODE = 0x0008

# Virtual Keys
VK_LMENU = 0xA4
VK_RMENU = 0xA5
VK_MENU = 0x12
VK_TAB = 0x09
VK_LWIN = 0x5B
VK_LSHIFT = 0xA0
VK_LCONTROL = 0xA2
VK_ESCAPE = 0x1B

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", ctypes.c_ushort),
        ("wScan", ctypes.c_ushort),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ctypes.c_size_t)
    ]

class INPUT_UNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("padding", ctypes.c_ubyte * 32)]

class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_ulong),
        ("padding", ctypes.c_ulong),
        ("iu", INPUT_UNION)
    ]

def send_key_sc(vk, down=True):
    scancode = ctypes.windll.user32.MapVirtualKeyW(vk, 0)
    flags = KEYEVENTF_SCANCODE
    if not down:
        flags |= KEYEVENTF_KEYUP
    
    iu = INPUT_UNION()
    # wVk must be 0 when using KEYEVENTF_SCANCODE
    iu.ki = KEYBDINPUT(0, scancode, flags, 0, 0)
    x = INPUT(type=INPUT_KEYBOARD, padding=0, iu=iu)
    ctypes.windll.user32.SendInput(1, ctypes.pointer(x), ctypes.sizeof(x))

def tap_key_sc(vk):
    send_key_sc(vk, True)
    time.sleep(0.05)
    send_key_sc(vk, False)

def modifier_flush():
    print("Flushing all modifiers (L/R Alt, Ctrl, Shift)...")
    mods = [VK_LMENU, VK_RMENU, VK_LCONTROL, 0xA3, VK_LSHIFT, 0xA1]
    for m in mods:
        send_key_sc(m, False) # Force Up
        time.sleep(0.01)

def run_test():
    print("VERSION: 6.0 - SCAN CODE MATCHING - MODIFIER FLUSH")
    print("Starting in 5 seconds...")
    time.sleep(5)

    print("Step 1: Windows Key Verification")
    tap_key_sc(VK_LWIN)
    time.sleep(1.0)
    
    print("Step 2: Alt Down (Left Alt Scan Code 0x38)")
    send_key_sc(VK_LMENU, True)
    time.sleep(1.0)
    
    for i in range(2):
        print(f"Step 3.{i+1}: Tab Tap")
        tap_key_sc(VK_TAB)
        time.sleep(1.0)
        
    print("Step 4: Alt Up (Left Alt Scan Code 0x38)")
    send_key_sc(VK_LMENU, False)
    time.sleep(0.5)
    
    modifier_flush()
    
    print("Step 5: Escape Tap")
    tap_key_sc(VK_ESCAPE)
    
    print("Done. Please check if Alt is STILL stuck.")

if __name__ == "__main__":
    run_test()
