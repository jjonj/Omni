using System;
using System.Drawing;
using System.Windows.Forms;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic
{
    public static class LayoutHelper
    {
        public static Rectangle CalculateBounds(WindowLayout layout, Screen? targetScreen = null)
        {
            var screen = targetScreen ?? Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary screen found.");
            var area = screen.WorkingArea;

            if (!layout.UseRatio)
            {
                return new Rectangle((int)layout.X, (int)layout.Y, (int)layout.Width, (int)layout.Height);
            }

            int x = area.X + (int)(area.Width * layout.RatioX);
            int y = area.Y + (int)(area.Height * layout.RatioY);
            int width = (int)(area.Width * layout.RatioWidth);
            int height = (int)(area.Height * layout.RatioHeight);

            return new Rectangle(x, y, width, height);
        }

        public static WindowLayout FromRectangle(Rectangle rect, Screen? targetScreen = null)
        {
            var screen = targetScreen ?? Screen.FromRectangle(rect) ?? Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary screen found.");
            var area = screen.WorkingArea;

            return new WindowLayout
            {
                UseRatio = true,
                X = rect.X,
                Y = rect.Y,
                Width = rect.Width,
                Height = rect.Height,
                RatioX = (double)(rect.X - area.X) / area.Width,
                RatioY = (double)(rect.Y - area.Y) / area.Height,
                RatioWidth = (double)rect.Width / area.Width,
                RatioHeight = (double)rect.Height / area.Height
            };
        }
    }
}
