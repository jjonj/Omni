"""Install/verify NORMAL and SOFA Voice scenes on the running OmniHub.

python TestScripts/System/configure_displayfusion_scenes.py
python TestScripts/System/configure_displayfusion_scenes.py --verify --exercise

--exercise switches to NORMAL, then SOFA through Jarvis's voice inbox.
Requires websocket-client. Existing unrelated macros are preserved.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import time
import urllib.request
import uuid
import winreg

import websocket

ROOT = Path(__file__).resolve().parents[2]
OMNI_DATA = Path(os.environ['APPDATA']) / 'OmniSync'
DEPLOYED = OMNI_DATA / 'Automation' / 'Set-DisplayFusionProfile.ps1'
SOURCE = ROOT / 'TestScripts' / 'Set-DisplayFusionProfile.ps1'
PROFILES = ('NORMAL', 'SOFA')


class Hub:
    def __init__(self):
        self.ws = websocket.create_connection('ws://127.0.0.1:5000/signalrhub', timeout=10)
        self.pending = []
        self.sequence = 0
        self.ws.send(json.dumps({'protocol': 'json', 'version': 1}) + '\x1e')
        reply = self.receive()
        if reply.get('error'):
            raise RuntimeError(reply['error'])
        config_path = Path(os.environ.get('OMNIHUB_AUTH_CONFIG') or Path(os.environ['LOCALAPPDATA']) / 'OmniHub/config/appsettings.json')
        config = json.loads(config_path.read_text(encoding='utf-8-sig'))
        self.api_key = os.environ.get('OMNI_API_KEY', config['AuthApiKey'])
        if self.invoke('Authenticate', self.api_key) is not True:
            raise RuntimeError('Hub authentication failed; set OMNI_API_KEY to the running Hub key.')

    def receive(self):
        while not self.pending:
            frame = self.ws.recv()
            if not frame:
                raise RuntimeError('Hub closed connection')
            self.pending.extend(json.loads(part) for part in frame.split('\x1e') if part)
        return self.pending.pop(0)

    def invoke(self, target, *arguments):
        self.sequence += 1
        invocation = str(self.sequence)
        self.ws.send(json.dumps({'type': 1, 'invocationId': invocation,
                                 'target': target, 'arguments': list(arguments)}) + '\x1e')
        while True:
            reply = self.receive()
            if reply.get('type') == 3 and reply.get('invocationId') == invocation:
                if reply.get('error'):
                    raise RuntimeError(reply['error'])
                return reply.get('result')


def macro_script(profile):
    return (f'runwait powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass '
            f'-File "{DEPLOYED}" -Profile {profile}')


def profile_last_used(name):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                        r'Software\Binary Fortress Software\DisplayFusion\MonitorConfig') as root:
        for index in range(winreg.QueryInfoKey(root)[0]):
            with winreg.OpenKey(root, winreg.EnumKey(root, index)) as key:
                if winreg.QueryValueEx(key, 'Name')[0] == name:
                    return winreg.QueryValueEx(key, 'DateTimeLastUsedUTC')[0]
    raise RuntimeError(f'DisplayFusion profile missing: {name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true', help='Read-only configuration verification')
    parser.add_argument('--exercise', action='store_true', help='Actually switch both profiles via voice inbox')
    args = parser.parse_args()
    hub = Hub()
    try:
        macros = hub.invoke('GetMacros')
        if not args.verify:
            DEPLOYED.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE, DEPLOYED)
            for profile in PROFILES:
                matches = [m for m in macros if m['name'].strip().casefold() == profile.casefold()
                           and m['category'].strip().casefold() == 'voice']
                if len(matches) > 1:
                    raise RuntimeError(f'Duplicate Voice scenes named {profile}; resolve before updating.')
                script = macro_script(profile)
                if matches and matches[0]['script'] != script:
                    raise RuntimeError(f'Existing {profile} scene has a different action; refusing to overwrite.')
                macro = matches[0] if matches else {
                    'id': str(uuid.uuid4()), 'name': profile, 'category': 'Voice',
                    'isPinned': False, 'script': script}
                hub.invoke('SaveMacro', macro)
                print(f'Saved Voice scene: {profile}')
        assert DEPLOYED.read_bytes() == SOURCE.read_bytes(), 'Deployed confirmation script differs from source'
        macros = hub.invoke('GetMacros')
        for profile in PROFILES:
            matches = [m for m in macros if m['name'].strip().casefold() == profile.casefold()
                       and m['category'].strip().casefold() == 'voice']
            assert len(matches) == 1, f'Expected one Voice scene for {profile}'
            assert matches[0]['script'] == macro_script(profile), f'Wrong action for {profile}'
            profile_last_used(profile)
            print(f'PASS {profile}: unique Voice scene, correct command, saved DisplayFusion profile exists')
        if args.exercise:
            for profile in PROFILES:
                previous = profile_last_used(profile)
                request = urllib.request.Request('http://127.0.0.1:5000/api/voice-inbox',
                    data=json.dumps({'text': f'activate {profile}', 'source': 'displayfusion-scene-test',
                                     'sourceId': str(uuid.uuid4())}).encode(),
                    headers={'Content-Type': 'application/json', 'X-Omni-Api-Key': hub.api_key})
                with urllib.request.urlopen(request, timeout=10) as response:
                    result = json.load(response)
                assert result.get('status') == 'handled', f'Voice inbox did not handle {profile}: {result}'
                assert (result.get('commandId') == f'routine.{profile.lower()}'
                        or result.get('handler') == 'Macro'), f'Wrong voice inbox route for {profile}: {result}'
                deadline = time.monotonic() + 75
                while profile_last_used(profile) == previous and time.monotonic() < deadline:
                    time.sleep(0.2)
                assert profile_last_used(profile) != previous, f'{profile} did not complete and persist'
                print(f'PASS Jarvis voice inbox activated and persisted {profile}')
        print('ALL PASSED')
    finally:
        hub.ws.close()


if __name__ == '__main__':
    main()
