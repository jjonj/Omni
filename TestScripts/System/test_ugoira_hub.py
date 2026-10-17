"""Verify the Hub-owned downloader against the running OmniHub."""
import json
import contextlib
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = os.environ.get('OMNI_HUB_URL', 'http://127.0.0.1:3333').rstrip('/')


@contextlib.contextmanager
def download_folder():
    root = Path(tempfile.gettempdir()).resolve()
    folder = root / f'omni-ugoira-{uuid.uuid4().hex}'
    # Use normal inherited folder permissions, matching a folder chosen in Explorer.
    folder.mkdir()
    try:
        yield folder
    finally:
        assert folder.resolve().parent == root
        shutil.rmtree(folder)


def request(path, payload=None, origin=BASE, headers=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data)
    if data is not None:
        req.add_header('Content-Type', 'application/json')
        req.add_header('Origin', origin)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    return urllib.request.urlopen(req, timeout=15)


def main():
    with request('/misc/ugoira/ugoira.html') as response:
        page = response.read().decode()
    assert '/api/ugoira/start' in page and 'Download folder on this PC' in page
    print('PASS page is served by OmniHub with download location selection')
    for payload, origin, expected in (
        ({'text': '123', 'output': 'relative'}, BASE, 400),
        ({'text': '123', 'output': str(ROOT)}, 'https://example.com', 403),
    ):
        try:
            request('/api/ugoira/start', payload, origin)
            raise AssertionError('Invalid request was accepted')
        except urllib.error.HTTPError as exc:
            assert exc.code == expected, (exc.code, expected)
    print('PASS folder validation and cross-origin write rejection')
    with download_folder() as folder:
        payload = {'text': 'https://www.pixiv.net/en/artworks/150267771\n150267771\ninvalid', 'output': str(folder)}
        with request('/api/ugoira/start', payload) as response:
            assert response.status == 202
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            with request('/api/ugoira/status') as response:
                state = json.load(response)
            if not state['running']:
                break
            time.sleep(.25)
        else:
            raise AssertionError('Batch did not finish within 120 seconds')
        assert state['summary'] == 'Finished: 1 saved, 1 skipped, 1 failed.', state
        assert Path(state['output']) == folder
        assert (folder / '150267771.mp4').stat().st_size > 0
        assert len((folder / '150267771.mp4').read_bytes()) > 0
        print('PASS original frames downloaded into the requested folder; duplicates and invalid links handled')
        with request('/api/ugoira/videos/150267771.mp4', headers={'Range': 'bytes=0-99'}) as response:
            assert response.status == 206 and len(response.read()) == 100
        decoded = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(folder / '150267771.mp4'), '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1'], capture_output=True)
        assert decoded.returncode == 0, decoded.stderr.decode(errors='replace')
        pixels = decoded.stdout
        assert len(pixels) == 608 * 1080 * 3 * 2
        assert sum(pixels) / len(pixels) > 20
        print('PASS video range delivery and both visible, nonblack decoded frames')


if __name__ == '__main__':
    main()
