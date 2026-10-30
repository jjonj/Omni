"""Run one batch under OmniHub, emitting progress as JSON lines."""
import json
import logging
import shutil
import sys
from pathlib import Path

import download_ugoira as tool


def emit(**event):
    print(json.dumps(event), flush=True)


def main():
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    job = json.loads(sys.stdin.readline())
    output = Path(job['output'])
    lines = job['lines']
    local_ffmpeg = tool.ROOT / 'ffmpeg.exe'
    ffmpeg = str(local_ffmpeg) if local_ffmpeg.exists() else shutil.which('ffmpeg')
    if not ffmpeg:
        raise RuntimeError('FFmpeg was not found in the Hub environment. Put ffmpeg.exe beside worker.py or add it to PATH.')
    cookies = tool.ROOT / 'cookies.txt'
    def fresh_opener():
        return tool.make_browser_opener(cookies if cookies.exists() else None)
    opener = fresh_opener()
    logging.info('Batch start: %d entries; output=%s; ffmpeg=%s', len(lines), output, ffmpeg)
    saved = skipped = failed = 0
    seen = set()
    for index, value in enumerate(lines):
        def update(**values):
            emit(type='item', index=index, **values)
        try:
            identifier = tool.artwork_id(value)
            logging.info('Entry %d: artwork=%s', index, identifier)
            update(id=identifier)
            if identifier in seen:
                skipped += 1
                update(status='Skipped', detail='Duplicate artwork.')
                continue
            seen.add(identifier)
            update(status='Working', detail='Fetching animation metadata...')
            try:
                result, target = tool.download(opener, identifier, output, ffmpeg,
                                              progress=lambda message: update(detail=message))
            except tool.PixivLoginRequired:
                # Firefox may have renewed its session after the batch started.
                update(detail='Refreshing Firefox Pixiv cookies and retrying...')
                opener = fresh_opener()
                result, target = tool.download(opener, identifier, output, ffmpeg,
                                              progress=lambda message: update(detail=message))
            if result == 'SAVED':
                saved += 1
                update(status='Saved', detail=target.name, video=f'/api/ugoira/videos/{target.name}')
            else:
                skipped += 1
                update(status='Skipped', detail='Already downloaded.', video=f'/api/ugoira/videos/{target.name}')
            logging.info('Entry %d finished: %s; file=%s; bytes=%d', index, result, target, target.stat().st_size)
        except Exception as exc:
            failed += 1
            logging.exception('Entry %d failed', index)
            update(status='Failed', detail=str(exc))
    summary = f'Finished: {saved} saved, {skipped} skipped, {failed} failed.'
    emit(type='done', summary=summary)
    logging.info(summary)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        logging.exception('Worker failed before batch completion')
        sys.exit(1)
