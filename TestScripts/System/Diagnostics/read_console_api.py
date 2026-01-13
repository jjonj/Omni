import ctypes
import sys
import pygetwindow as gw

STDOUT_HANDLE = -11
kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)

class COORD(ctypes.Structure):
    _fields_ = [("X", ctypes.c_short), ("Y", ctypes.c_short)]

class SMALL_RECT(ctypes.Structure):
    _fields_ = [("Left", ctypes.c_short), ("Top", ctypes.c_short),
                ("Right", ctypes.c_short), ("Bottom", ctypes.c_short)]

class CONSOLE_SCREEN_BUFFER_INFO(ctypes.Structure):
    _fields_ = [("dwSize", COORD),
                ("dwCursorPosition", COORD),
                ("wAttributes", ctypes.c_ushort),
                ("srWindow", SMALL_RECT),
                ("dwMaximumWindowSize", COORD)]

def read_console_by_title(window_title):
    try:
        window = gw.getWindowsWithTitle(window_title)[0]
    except Exception as e:
        return f"Error: Window not found: {e}"

    lpdw_process_id = ctypes.c_ulong()
    ctypes.windll.user32.GetWindowThreadProcessId(window._hWnd, ctypes.byref(lpdw_process_id))
    pid = lpdw_process_id.value

    # Free our own console if we have one
    kernel32.FreeConsole()

    if not kernel32.AttachConsole(pid):
        return f"Error: Failed to attach console to PID {pid}. Error code: {ctypes.get_last_error()}"

    result = "Failed to capture."
    try:
        h_console = kernel32.GetStdHandle(STDOUT_HANDLE)
        csbi = CONSOLE_SCREEN_BUFFER_INFO()
        if kernel32.GetConsoleScreenBufferInfo(h_console, ctypes.byref(csbi)):
            width = csbi.dwSize.X
            height = csbi.dwSize.Y
            num_chars = width * height
            buffer = ctypes.create_unicode_buffer(num_chars)
            chars_read = ctypes.c_ulong()
            origin = COORD(0, 0)
            
            if kernel32.ReadConsoleOutputCharacterW(h_console, buffer, num_chars, origin, ctypes.byref(chars_read)):
                text = buffer.value
                lines = [text[i:i+width] for i in range(0, len(text), width)]
                result = "\n".join(line.rstrip() for line in lines if line.strip())
    finally:
        kernel32.FreeConsole()
        # Re-attach to a new console so we can print again if we want (but usually we're in a wrapper)
        kernel32.AllocConsole()

    return result

if __name__ == "__main__":
    content = read_console_by_title("Gemini - gemini-cli")
    # After AllocConsole, we might need to reopen stdout
    sys.stdout = open('CONOUT$', 'w')
    print("--- CAPTURE ---")
    print(content[-1000:] if content else "No content")
    print("--- END ---")
    import os
    os.system('pause')
