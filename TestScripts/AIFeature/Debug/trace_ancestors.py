import psutil
import os

def trace():
    try:
        p = psutil.Process(os.getpid())
        while p:
            print(f"PID: {p.pid}, Name: {p.name()}, Cmdline: {p.cmdline()}")
            p = p.parent()
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    trace()
