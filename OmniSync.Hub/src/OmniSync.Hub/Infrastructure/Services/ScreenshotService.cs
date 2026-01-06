using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
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

        public byte[] CapturePrimaryScreenToMemory(double scale = 1.0, long quality = 50L)
        {
            var primaryScreen = Screen.PrimaryScreen;
            if (primaryScreen == null) return Array.Empty<byte>();

            int sourceWidth = primaryScreen.Bounds.Width;
            int sourceHeight = primaryScreen.Bounds.Height;
            int top = primaryScreen.Bounds.Top;
            int left = primaryScreen.Bounds.Left;

            int targetWidth = (int)(sourceWidth * scale);
            int targetHeight = (int)(sourceHeight * scale);

            // Sanity check
            if (targetWidth <= 0) targetWidth = 1;
            if (targetHeight <= 0) targetHeight = 1;

            using (Bitmap sourceBitmap = new Bitmap(sourceWidth, sourceHeight))
            {
                using (Graphics g = Graphics.FromImage(sourceBitmap))
                {
                    g.CopyFromScreen(left, top, 0, 0, sourceBitmap.Size);
                }

                Bitmap? targetBitmap = null;
                try
                {
                    if (scale >= 0.99 && scale <= 1.01)
                    {
                        targetBitmap = sourceBitmap;
                    }
                    else
                    {
                        targetBitmap = new Bitmap(sourceBitmap, targetWidth, targetHeight);
                    }

                    using (MemoryStream ms = new MemoryStream())
                    {
                        var encoder = ImageCodecInfo.GetImageEncoders().FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);
                        if (encoder == null) return Array.Empty<byte>();

                        var parameters = new EncoderParameters(1);
                        parameters.Param[0] = new EncoderParameter(Encoder.Quality, quality);

                        targetBitmap.Save(ms, encoder, parameters);
                        return ms.ToArray();
                    }
                }
                finally
                {
                    if (targetBitmap != null && targetBitmap != sourceBitmap)
                    {
                        targetBitmap.Dispose();
                    }
                }
            }
        }
    }
}
