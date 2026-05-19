import time
import subprocess
import json

def get_procs():
    output = subprocess.check_output(['powershell', '-NoProfile', '-Command', 
        'Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name | ConvertTo-Json']).decode()
    return json.loads(output)

print('Waiting 10 seconds for processes to settle...')
time.sleep(10)

procs_raw = get_procs()
if isinstance(procs_raw, dict):
    procs = [procs_raw]
else:
    procs = procs_raw

pid_map = {p['ProcessId']: p for p in procs}

def trace_up(pid):
    path = []
    curr = pid
    while curr in pid_map:
        p = pid_map[curr]
        path.append(f"{p['ProcessId']}:{p['Name']}")
        if p['ParentProcessId'] == curr or p['ParentProcessId'] == 0: break
        curr = p['ParentProcessId']
    return ' -> '.join(path)

# Targets
# We'll search for 'node' processes that might be the new sessions if PIDs changed
node_procs = [p['ProcessId'] for p in procs if 'node' in p['Name'].lower()]

print(f"Found {len(node_procs)} node processes.")

for t in node_procs:
    print(f"\nTrace for {t}:")
    print(trace_up(t))

# Find ALL Terminal, conhost, OpenConsole processes
print("\nPotential Bridges:")
for p in procs:
    name = p['Name'].lower()
    if 'terminal' in name or 'conhost' in name or 'openconsole' in name:
        print(f"{p['ProcessId']}:{p['Name']} (Parent: {p['ParentProcessId']})")
