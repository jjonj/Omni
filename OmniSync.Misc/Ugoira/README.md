# Ugoira browser downloader

1. Open http://127.0.0.1:3333/misc/ugoira/ugoira.html while OmniHub is running.
2. Paste one Pixiv artwork link per line, or select a newline-separated text file.
3. Choose a download folder with **Choose folder...**, or enter its full path.
   Clicking **Download MP4s** without a folder opens the Windows folder picker
   on the Hub PC; that window is modal, so the page waits until you pick or
   cancel it. The page gives up waiting after two minutes and says so. The last
   folder you used is remembered in `%LOCALAPPDATA%\OmniSync\ugoira-last-output.txt`
   and prefills the box, so the picker is normally needed only once.
4. Watch progress on the page. Videos are saved in your chosen folder, named by
   artwork ID. Press play on a preview to watch it loop continuously.

OmniHub serves the page and owns download jobs. No separate Python web server or
launcher window is required. Closing the page does not stop the batch; reopening
it recovers progress while the Hub remains running. Hub shutdown stops its worker
and FFmpeg processes. Restarting the Hub does not resume interrupted jobs: submit
the list again; completed videos are skipped.
Python 3.10+ and FFmpeg with the libx264 encoder are required. Both are already
available on this computer. There are no Python packages to install.
Hub builds copy this folder's Python files and `ugoira.html` into
`Misc/Ugoira` beside the Hub executable. FFmpeg must be on the Hub's PATH, or
`ffmpeg.exe` can be placed in that deployed folder.

Blank lines and lines beginning with `#` are ignored. Numeric artwork IDs work
too. Duplicate IDs and existing nonempty MP4 files are skipped. Failed entries
are reported with their line number, and the rest of the list continues.
Submit the same list again to retry failed downloads. Refreshing the page
preserves the current batch while the server is running.

The command-line downloader remains available:

```powershell
python download_ugoira.py links.txt
python download_ugoira.py my-links.txt --output "E:\Videos\Pixiv"
python download_ugoira.py links.txt --overwrite
python download_ugoira.py links.txt --ffmpeg "E:\Tools\ffmpeg.exe"
python download_ugoira.py links.txt --cookies "E:\Private\pixiv-cookies.txt"
```

For artwork requiring login in the browser page, place your own Pixiv cookies in
Netscape text format in `cookies.txt` in the deployed `Misc/Ugoira` folder. The
worker reads it at the start of each batch. For the command-line tool, use `--cookies`.
The tool does not read browser profiles automatically.
Keep the cookie file private; it contains login credentials. Still images,
deleted artwork, and inaccessible artwork produce a failure message.

The script requests the original-resolution frame ZIP and uses all of Pixiv's
frame delays, including the final frame. The MP4 uses H.264 Baseline, contains one play
of the animation, and has no audio. Odd image dimensions are padded by one
pixel when necessary for compatibility. A common millisecond tick determines
the frame rate; frames repeat as needed to preserve different delays exactly.
Unusual timing can result in a high frame rate. Temporary archives are removed
after processing, and a completed video is moved into place only after conversion
succeeds. Exit codes are 0 for success, 1 if any entry fails, and 130 for cancellation.

Request, worker, network, frame timing, encoder, output, and failure diagnostics
appear in the Hub activity log with the `[Ugoira]` prefix. Browser connection
failures and reconnections also appear in its developer console.

The page shows a grey diagnostics line under the summary with its own origin and
the result of the last `/api/ugoira/status` poll, and an **OmniHub log** panel
with the last 400 Hub-side messages. Both exist so a stalled page can be told
apart from a stalled Hub. The same log is readable from a terminal:

```powershell
(Invoke-RestMethod http://127.0.0.1:3333/api/ugoira/log).entries | Select-Object -Last 30
```

The whole feature is loopback-only; requests from any other address get HTTP 403
with a JSON explanation, so the page can report the real reason instead of a
generic reconnect message.

Verification scripts:

```powershell
python -B TestScripts/System/test_ugoira_conversion.py -v
python -B TestScripts/System/test_ugoira_hub.py
```
