import contextlib
import io
import sys
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'OmniSync.Misc' / 'Ugoira'))
import download_ugoira as tool


def png(color):
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))
    header = struct.pack('!2I5B', 3, 3, 8, 2, 0, 0, 0)
    pixels = (b'\x00' + bytes(color) * 3) * 3
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


class DownloaderTests(unittest.TestCase):
    def test_links(self):
        for value in ('150267771', 'https://www.pixiv.net/en/artworks/150267771?x=1', 'https://www.pixiv.net/artworks/150267771/'):
            self.assertEqual(tool.artwork_id(value), '150267771')
        for value in ('https://example.com/artworks/123', 'https://www.pixiv.net/users/123', 'garbage'):
            with self.assertRaises(ValueError):
                tool.artwork_id(value)

    def test_failures_continue_and_duplicates_are_skipped(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            links = folder / 'links.txt'
            links.write_text('# comment\n\ninvalid\n1\n2\n2\n3\n', encoding='utf-8')
            def download(opener, identifier, output, ffmpeg, overwrite):
                if identifier == '1':
                    raise RuntimeError('unavailable')
                return 'SAVED', output / f'{identifier}.mp4'
            captured = io.StringIO()
            with patch.object(tool, 'download', side_effect=download) as mocked, contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
                code = tool.main([str(links), '-o', str(folder / 'out'), '--ffmpeg', 'ffmpeg'])
            self.assertEqual(code, 1)
            self.assertEqual([call.args[1] for call in mocked.call_args_list], ['1', '2', '3'])
            self.assertIn('2 saved, 1 skipped, 2 failed', captured.getvalue())

    def test_existing_video_does_not_make_network_request(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / '1.mp4').write_bytes(b'existing')
            with patch.object(tool, 'fetch') as mocked:
                self.assertEqual(tool.download(None, '1', folder, 'ffmpeg')[0], 'SKIP')
                mocked.assert_not_called()

    @unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg is required for conversion verification')
    def test_unequal_delays_last_frame_and_odd_dimensions(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            archive = folder / 'frames.zip'
            colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255)]
            frames = [{'file': f'{i}.png', 'delay': delay} for i, delay in enumerate((70, 110, 30))]
            with zipfile.ZipFile(archive, 'w') as images:
                for frame, color in zip(frames, colors):
                    images.writestr(frame['file'], png(color))
            video = folder / 'video.mp4'
            tool.convert(archive, frames, video, shutil.which('ffmpeg'))
            decoded = subprocess.run([shutil.which('ffmpeg'), '-hide_banner', '-i', str(video), '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1'], capture_output=True, check=True)
            self.assertIn('Duration: 00:00:00.21', decoded.stderr.decode())
            frame_size = 4 * 4 * 3
            self.assertEqual(len(decoded.stdout), frame_size * 21)
            for index, color in enumerate([colors[0]] * 7 + [colors[1]] * 11 + [colors[2]] * 3):
                actual = decoded.stdout[index * frame_size:index * frame_size + 3]
                dominant = color.index(255)
                self.assertGreater(actual[dominant], 200, (index, actual, color))
                self.assertTrue(all(actual[channel] < 60 for channel in range(3) if channel != dominant), (index, actual, color))


if __name__ == '__main__':
    unittest.main()
