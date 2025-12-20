import os
import psutil

def print_ancestry():
    p = psutil.Process(os.getpid())
    while p:
        print(f"PID: {p.pid}, Name: {p.name()}")
        p = p.parent()

if __name__ == "__main__":
    print_ancestry()
