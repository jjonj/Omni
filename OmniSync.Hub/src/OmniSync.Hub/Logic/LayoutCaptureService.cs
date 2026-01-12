using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic
{
    public class LayoutCaptureService
    {
        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumDesktopWindows(IntPtr hDesktop, EnumWindowsProc lpFn, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll")]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll")]
        private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }

        public List<CapturedWindow> CaptureCurrentLayout()
        {
            var windows = new List<CapturedWindow>();

            EnumDesktopWindows(IntPtr.Zero, (hWnd, lParam) =>
            {
                if (IsWindowVisible(hWnd))
                {
                    GetWindowRect(hWnd, out RECT rect);
                    int width = rect.Right - rect.Left;
                    int height = rect.Bottom - rect.Top;

                    if (width > 100 && height > 100) // Ignore tiny/hidden windows
                    {
                        GetWindowThreadProcessId(hWnd, out uint pid);
                        try
                        {
                            var proc = Process.GetProcessById((int)pid);
                            var path = proc.MainModule?.FileName;
                            
                            if (string.IsNullOrEmpty(path)) return true;

                            // Ignore Hub itself and system shells
                            if (path.EndsWith("OmniSync.Hub.exe", StringComparison.OrdinalIgnoreCase)) return true;
                            if (path.EndsWith("explorer.exe", StringComparison.OrdinalIgnoreCase))
                            {
                                // Handle Explorer folders if possible
                                string folderPath = WindowDetector.GetPathForExplorerWindow(hWnd);
                                if (!string.IsNullOrEmpty(folderPath))
                                {
                                    windows.Add(new CapturedWindow
                                    {
                                        Type = ProjectActionType.OpenFolder,
                                        Path = folderPath,
                                        Layout = LayoutHelper.FromRectangle(new Rectangle(rect.Left, rect.Top, width, height))
                                    });
                                }
                            }
                            else
                            {
                                windows.Add(new CapturedWindow
                                {
                                    Type = ProjectActionType.RunProgram,
                                    Path = path,
                                    Layout = LayoutHelper.FromRectangle(new Rectangle(rect.Left, rect.Top, width, height))
                                });
                            }
                        }
                        catch
                        {
                            // Access denied or process exited
                        }
                    }
                }
                return true;
            }, IntPtr.Zero);

            return windows;
        }
    }

    public class CapturedWindow
    {
        public ProjectActionType Type { get; set; }
        public string Path { get; set; } = "";
        public WindowLayout Layout { get; set; } = new();
    }
}
