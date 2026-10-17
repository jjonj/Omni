#!/usr/bin/env python3
"""Download newline-separated Pixiv ugoira links as MP4 videos."""

import argparse
import http.cookiejar
import json
import logging
import math
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
LOG = logging.getLogger('ugoira')


def artwork_id(value):
    if value.isascii() and value.isdecimal():
        return str(int(value))
    url = urllib.parse.urlsplit(value)
    if url.scheme not in ('http', 'https') or url.hostname not in ('pixiv.net', 'www.pixiv.net'):
        raise ValueError('Expected a Pixiv artwork URL or numeric artwork ID')
    parts = url.path.strip('/').split('/')
    if len(parts) >= 2 and parts[-2] == 'artworks' and parts[-1].isascii() and parts[-1].isdecimal():
        return str(int(parts[-1]))
    raise ValueError('Expected a URL like https://www.pixiv.net/en/artworks/150267771')


def make_opener(cookies=None):
    jar = http.cookiejar.CookieJar()
    if cookies:
        jar = http.cookiejar.MozillaCookieJar(str(cookies))
        jar.load(ignore_discard=True)
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.addheaders = [('User-Agent', 'Mozilla/5.0'), ('Referer', 'https://www.pixiv.net/')]
    return opener


def fetch(opener, url, destination=None):
    for attempt in range(3):
        try:
            LOG.info('HTTP request attempt=%d url=%s destination=%s', attempt + 1, url, destination)
            with opener.open(url, timeout=60) as response:
                LOG.info('HTTP response status=%s bytes=%s', response.status, response.headers.get('Content-Length'))
                if destination is None:
                    metadata = json.load(response)
                    LOG.info('Metadata decoded; error=%s; frames=%d', metadata.get('error'), len((metadata.get('body') or {}).get('frames') or []))
                    return metadata
                with destination.open('wb') as output:
                    shutil.copyfileobj(response, output)
                LOG.info('Archive saved; bytes=%d', destination.stat().st_size)
                return
        except urllib.error.HTTPError as exc:
            LOG.warning('HTTP failure; attempt=%d status=%s', attempt + 1, exc.code)
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise RuntimeError(f'Pixiv returned HTTP {exc.code}. If login is required, use --cookies cookies.txt.') from None
        except (urllib.error.URLError, TimeoutError):
            LOG.warning('Network failure; attempt=%d', attempt + 1, exc_info=True)
            if attempt == 2:
                raise RuntimeError('Could not reach Pixiv after 3 attempts') from None
        time.sleep(2 ** (attempt + 1))


def frame_info(metadata):
    if metadata.get('error'):
        raise ValueError(metadata.get('message') or 'Pixiv rejected the artwork request')
    body = metadata.get('body') or {}
    frames = body.get('frames')
    source = body.get('originalSrc') or body.get('src')
    if not frames or not source:
        raise ValueError('No ugoira animation found (this may be a still image or require login)')
    for frame in frames:
        if not isinstance(frame.get('file'), str) or not frame['file']:
            raise ValueError('Pixiv returned an invalid frame filename')
        delay = frame.get('delay')
        if type(delay) is not int or delay <= 0:
            raise ValueError('Pixiv returned an invalid frame duration')
    url = urllib.parse.urlsplit(source)
    if url.scheme != 'https' or not (url.hostname == 'pximg.net' or (url.hostname or '').endswith('.pximg.net')):
        raise ValueError('Unexpected frame archive URL')
    return source, frames


def convert(archive, frames, destination, ffmpeg):
    # A common millisecond tick preserves every frame delay, including the final frame.
    tick = math.gcd(*(frame['delay'] for frame in frames), 1000)
    LOG.info('Encoding start; frames=%d delays=%s tick=%d fps=%d output=%s', len(frames), [f['delay'] for f in frames], tick, 1000 // tick, destination)
    command = [
        ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
        '-f', 'image2pipe', '-framerate', str(1000 // tick), '-i', 'pipe:0',
        '-an', '-c:v', 'libx264', '-profile:v', 'baseline', '-preset', 'medium', '-crf', '18',
        '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-pix_fmt', 'yuv420p',
        '-movflags', '+faststart', str(destination),
    ]
    with zipfile.ZipFile(archive) as images, tempfile.TemporaryFile() as errors:
        # Read named members directly; archive paths are never extracted to disk.
        missing = [frame['file'] for frame in frames if frame['file'] not in images.namelist()]
        if missing:
            raise ValueError(f'Frame archive is missing {missing[0]}')
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=errors, stdout=subprocess.DEVNULL)
        LOG.info('FFmpeg started; pid=%d command=%s', process.pid, command)
        try:
            try:
                for frame in frames:
                    data = images.read(frame['file'])
                    LOG.info('Frame %s; bytes=%d; repeats=%d', frame['file'], len(data), frame['delay'] // tick)
                    for _ in range(frame['delay'] // tick):
                        process.stdin.write(data)
                process.stdin.close()
            except BrokenPipeError:
                pass
            status = process.wait()
            LOG.info('FFmpeg exit=%d', status)
            if status:
                errors.seek(0)
                detail = errors.read().decode('utf-8', errors='replace').strip()
                raise RuntimeError(f'FFmpeg failed: {detail or status}')
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()
            if not process.stdin.closed:
                process.stdin.close()


def cookies_hint(identifier, reason):
    return (f'Artwork {identifier} {reason}. '
            'Add a Netscape-format Pixiv cookies.txt beside worker.py, or pass --cookies PATH, then retry.')


def diagnose_missing(opener, identifier):
    """Explain a ugoira_meta 404 using the artwork metadata Pixiv still returns."""
    try:
        body = fetch(opener, f'https://www.pixiv.net/ajax/illust/{identifier}?lang=en').get('body') or {}
    except RuntimeError:
        return cookies_hint(identifier, 'is not visible without login (deleted, private, or restricted)')
    restrict = body.get('xRestrict')
    if type(restrict) is int and restrict > 0:
        return cookies_hint(identifier, f'is age-restricted (xRestrict={restrict}) and Pixiv withholds its animation data')
    return (f'Artwork {identifier} is not an animation: Pixiv reports illustType={body.get("illustType")} with '
            f'{body.get("pageCount")} page(s) and no ugoira frames, so there is no video to build.')


def ugoira_meta(opener, identifier):
    try:
        return fetch(opener, f'https://www.pixiv.net/ajax/illust/{identifier}/ugoira_meta?lang=en')
    except RuntimeError as exc:
        if 'HTTP 404' not in str(exc):
            raise
        raise RuntimeError(diagnose_missing(opener, identifier)) from None


def download(opener, identifier, output, ffmpeg, overwrite=False, progress=print):
    target = output / f'{identifier}.mp4'
    LOG.info('Artwork %s; target=%s; overwrite=%s', identifier, target, overwrite)
    if target.exists() and target.stat().st_size > 0 and not overwrite:
        return 'SKIP', target
    source, frames = frame_info(ugoira_meta(opener, identifier))
    with tempfile.TemporaryDirectory(prefix='.ugoira-', dir=output) as working:
        working = Path(working)
        archive = working / 'frames.zip'
        progress(f'Downloading {len(frames)} frames ({sum(f["delay"] for f in frames) / 1000:g}s)...')
        fetch(opener, source, archive)
        progress('Converting to MP4...')
        convert(archive, frames, working / 'video.mp4', ffmpeg)
        # Create the published file in the chosen folder so it inherits that folder's
        # permissions, rather than the elevated worker's private temporary ACL.
        staged_path = output / f'.{identifier}-{uuid.uuid4().hex}.mp4'
        try:
            with (working / 'video.mp4').open('rb') as source, staged_path.open('xb') as staged:
                shutil.copyfileobj(source, staged)
            if sys.platform == 'win32':
                permissions = subprocess.run(['icacls', str(staged_path), '/reset'], capture_output=True, text=True)
                LOG.info('Restore output folder permissions; exit=%d; result=%s', permissions.returncode, permissions.stdout.strip())
                if permissions.returncode:
                    raise RuntimeError(f'Could not restore output folder permissions: {permissions.stderr.strip()}')
            staged_path.replace(target)
        finally:
            staged_path.unlink(missing_ok=True)
        LOG.info('Video published; target=%s; bytes=%d', target, target.stat().st_size)
        if sys.platform == 'win32':
            for path in (output, target):
                audit = subprocess.run(['icacls', str(path)], capture_output=True, text=True)
                LOG.info('Permission audit %s: %s %s', path, audit.stdout.strip(), audit.stderr.strip())
    return 'SAVED', target


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('list_file', nargs='?', type=Path, default=ROOT / 'links.txt', help='Text file with one Pixiv artwork URL or ID per line (default: links.txt beside script)')
    parser.add_argument('-o', '--output', type=Path, default=ROOT / 'downloads', help='Output folder (default: downloads beside script)')
    parser.add_argument('--cookies', type=Path, help='Optional Netscape-format Pixiv cookies file for artwork requiring login')
    parser.add_argument('--ffmpeg', help='FFmpeg executable path (default: beside script, then PATH)')
    parser.add_argument('--overwrite', action='store_true', help='Replace existing videos after successful conversion')
    args = parser.parse_args(argv)
    try:
        local_ffmpeg = ROOT / ('ffmpeg.exe' if sys.platform == 'win32' else 'ffmpeg')
        ffmpeg = args.ffmpeg or (str(local_ffmpeg) if local_ffmpeg.is_file() else shutil.which('ffmpeg'))
        if not ffmpeg:
            raise ValueError('FFmpeg was not found. Put ffmpeg.exe beside this script or use --ffmpeg PATH.')
        lines = args.list_file.read_text(encoding='utf-8-sig').splitlines()
        opener = make_opener(args.cookies)
        args.output.mkdir(parents=True, exist_ok=True)
    except (OSError, ValueError, http.cookiejar.LoadError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    seen = set()
    saved = skipped = failed = 0
    for number, line in enumerate(lines, 1):
        value = line.strip()
        if not value or value.startswith('#'):
            continue
        try:
            identifier = artwork_id(value)
            if identifier in seen:
                print(f'Line {number}: duplicate {identifier}, skipping.')
                skipped += 1
                continue
            seen.add(identifier)
            print(f'Line {number}: artwork {identifier}', flush=True)
            result, target = download(opener, identifier, args.output.resolve(), ffmpeg, args.overwrite)
            print(f'{result}: {target}', flush=True)
            if result == 'SAVED':
                saved += 1
            else:
                skipped += 1
        except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, KeyError) as exc:
            print(f'FAILED line {number}: {exc}', file=sys.stderr, flush=True)
            failed += 1
    print(f'\nFinished: {saved} saved, {skipped} skipped, {failed} failed.\nOutput: {args.output.resolve()}')
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('\nCancelled.', file=sys.stderr)
        sys.exit(130)
