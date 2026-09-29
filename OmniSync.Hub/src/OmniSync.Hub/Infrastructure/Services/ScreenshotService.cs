using Microsoft.Extensions.Logging;
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class ScreenshotService
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct CursorInfo
        {
            public int Size;
            public int Flags;
            public IntPtr Cursor;
            public System.Drawing.Point Position;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IconInfo
        {
            [MarshalAs(UnmanagedType.Bool)] public bool IsIcon;
            public int HotspotX;
            public int HotspotY;
            public IntPtr Mask;
            public IntPtr Color;
        }

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetCursorInfo(ref CursorInfo cursorInfo);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetIconInfo(IntPtr icon, out IconInfo iconInfo);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool DrawIconEx(IntPtr dc, int x, int y, IntPtr icon, int width, int height, int step, IntPtr brush, int flags);

        [DllImport("gdi32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool DeleteObject(IntPtr handle);

        private readonly ILogger<ScreenshotService> _logger;

        public ScreenshotService(ILogger<ScreenshotService> logger)
        {
            _logger = logger;
        }

        public void CapturePrimaryScreen(string filePath)
        {
            try
            {
                var primaryScreen = Screen.PrimaryScreen;
                if (primaryScreen == null)
                {
                    _logger.LogWarning("CapturePrimaryScreen: PrimaryScreen is null.");
                    return;
                }

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
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error in CapturePrimaryScreen");
            }
        }

        public byte[] CapturePrimaryScreenToMemory(double scale = 1.0, long quality = 50L, int monitor = 0)
        {
            try
            {
                var screens = Screen.AllScreens.OrderByDescending(screen => screen.Primary).ThenBy(screen => screen.DeviceName).ToArray();
                if (monitor < 0 || monitor >= screens.Length)
                {
                    return Array.Empty<byte>();
                }

                var bounds = screens[monitor].Bounds;
                int sourceWidth = bounds.Width;
                int sourceHeight = bounds.Height;
                int top = bounds.Top;
                int left = bounds.Left;

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
                        DrawCursor(g, bounds);
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
                            if (encoder == null)
                            {
                                _logger.LogError("JPEG encoder not found.");
                                return Array.Empty<byte>();
                            }

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
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error in CapturePrimaryScreenToMemory");
                return Array.Empty<byte>();
            }
        }

        private static void DrawCursor(Graphics graphics, Rectangle bounds)
        {
            var cursorInfo = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
            if (!GetCursorInfo(ref cursorInfo) || (cursorInfo.Flags & 1) == 0 || !bounds.Contains(cursorInfo.Position))
                return;

            if (!GetIconInfo(cursorInfo.Cursor, out var iconInfo))
                return;

            try
            {
                int x = cursorInfo.Position.X - bounds.Left;
                int y = cursorInfo.Position.Y - bounds.Top;
                float radius = Math.Max(24, bounds.Width / 40f);
                using (var halo = new Pen(Color.FromArgb(230, 255, 210, 0), Math.Max(4f, bounds.Width / 400f)))
                {
                    graphics.DrawEllipse(halo, x - radius, y - radius, radius * 2, radius * 2);
                }

                var dc = graphics.GetHdc();
                try
                {
                    DrawIconEx(dc, cursorInfo.Position.X - bounds.Left - iconInfo.HotspotX,
                        cursorInfo.Position.Y - bounds.Top - iconInfo.HotspotY,
                        cursorInfo.Cursor, 0, 0, 0, IntPtr.Zero, 3);
                }
                finally
                {
                    graphics.ReleaseHdc(dc);
                }
            }
            finally
            {
                if (iconInfo.Mask != IntPtr.Zero) DeleteObject(iconInfo.Mask);
                if (iconInfo.Color != IntPtr.Zero) DeleteObject(iconInfo.Color);
            }
        }
    }
}
