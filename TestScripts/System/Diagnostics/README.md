# Diagnostics Utilities

Low-level tools used for debugging process management, focus issues, and system hooks.

| File | Purpose |
| :--- | :--- |
| `diagnose_processes.py` | Lists all processes with detailed Parent/Child relationship info. |
| `diagnose_wmi_node.py` | Specifically searches for node.js processes via WMI. |
| `list_windows.py` | Lists all visible top-level windows and their class names. |
| `diagnose_focus.ps1` | Monitors and logs window focus changes in real-time. |
| `read_console_api.py` | Attempt to read raw console buffers from external processes. |
| `test_node_pipe.js` | Minimal reproduction for Named Pipe communication issues. |
| `poc_gemini_control.py` | Proof-of-concept for controlling the Gemini window via Win32. |
