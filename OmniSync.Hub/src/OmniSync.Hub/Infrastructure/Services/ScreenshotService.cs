using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Windows.Forms;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class ScreenshotService
    {
        public void CapturePrimaryScreen(string filePath)
        {
            var primaryScreen = Screen.PrimaryScreen;
            if (primaryScreen == null) return;

            int width = primaryScreen.Bounds.Width;
            int height = primaryScreen.Bounds.Height;
            int top = primaryScreen.Bounds.Top;
            int left = primaryScreen.Bounds.Left;

            using (Bitmap bitmap = new Bitmap(width, height))
            {
                using (Graphics g = Graphics.FromImage(bitmap))
                {
                    g.CopyFromScreen(left, top, 0, 0, bitmap.Size);
                }

                string? directory = Path.GetDirectoryName(filePath);
                if (directory != null && !Directory.Exists(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                bitmap.Save(filePath, ImageFormat.Jpeg);
            }
        }

        public byte[] CapturePrimaryScreenToMemory()
        {
            var primaryScreen = Screen.PrimaryScreen;
            if (primaryScreen == null) return Array.Empty<byte>();

            int width = primaryScreen.Bounds.Width;
            int height = primaryScreen.Bounds.Height;
            int top = primaryScreen.Bounds.Top;
            int left = primaryScreen.Bounds.Left;

            using (Bitmap bitmap = new Bitmap(width, height))
            {
                using (Graphics g = Graphics.FromImage(bitmap))
                {
                    g.CopyFromScreen(left, top, 0, 0, bitmap.Size);
                }

                using (MemoryStream ms = new MemoryStream())
                {
                    // Use a lower quality for streaming to reduce bandwidth
                    var encoder = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
                    var parameters = new EncoderParameters(1);
                    parameters.Param[0] = new EncoderParameter(Encoder.Quality, 50L); // 50% quality

                    bitmap.Save(ms, encoder, parameters);
                    return ms.ToArray();
                }
            }
        }
    }
}
