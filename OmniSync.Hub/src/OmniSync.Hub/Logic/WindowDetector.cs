using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace OmniSync.Hub.Logic
{
    public static class WindowDetector
    {
        public static bool IsProcessRunning(string executablePath, out int pid)
        {
            pid = -1;
            string fileName = Path.GetFileNameWithoutExtension(executablePath);
            var processes = Process.GetProcessesByName(fileName);
            
            foreach (var p in processes)
            {
                try
                {
                    // Check if the path matches if it's a full path
                    if (executablePath.Contains(Path.DirectorySeparatorChar))
                    {
                        if (string.Equals(p.MainModule?.FileName, executablePath, StringComparison.OrdinalIgnoreCase))
                        {
                            pid = p.Id;
                            return true;
                        }
                    }
                    else
                    {
                        // Just name match
                        pid = p.Id;
                        return true;
                    }
                } 
                catch 
                { 
                    /* Access denied or process exited */ 
                }
            }
            return false;
        }

        public static IntPtr FindWindowByTitle(string titlePart)
        {
            var procs = Process.GetProcesses();
            foreach (var p in procs)
            {
                if (p.MainWindowTitle.Contains(titlePart, StringComparison.OrdinalIgnoreCase))
                {
                    return p.MainWindowHandle;
                }
            }
            return IntPtr.Zero;
        }

        /// <summary>
        /// Uses COM to find if an Explorer window is already looking at a specific path.
        /// Returns the HWND of the window if found.
        /// </summary>
        public static IntPtr GetExplorerWindowForPath(string path)
        {
            try
            {
                Type? shellType = Type.GetTypeFromProgID("Shell.Application");
                if (shellType == null) return IntPtr.Zero;

                dynamic? shell = Activator.CreateInstance(shellType);
                if (shell == null) return IntPtr.Zero;

                dynamic windows = shell.Windows();
                for (int i = 0; i < windows.Count; i++)
                {
                    dynamic window = windows.Item(i);
                    if (window == null) continue;

                    string name = window.Name;
                    if (name == "File Explorer" || name == "Windows Explorer")
                    {
                        string location = window.LocationURL;
                        if (string.IsNullOrEmpty(location)) continue;

                        try
                        {
                            Uri uri = new Uri(location);
                            string localPath = uri.LocalPath;
                            if (string.Equals(localPath.TrimEnd('\\'), path.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                            {
                                return (IntPtr)window.HWND;
                            }
                        }
                        catch 
                        { 

                        }
                    }
                }
            }
            catch 
            { 

            }
            return IntPtr.Zero;
        }

        public static string? GetPathForExplorerWindow(IntPtr hwnd)
        {
            try
            {
                Type? shellType = Type.GetTypeFromProgID("Shell.Application");
                if (shellType == null) return null;

                dynamic? shell = Activator.CreateInstance(shellType);
                if (shell == null) return null;

                dynamic windows = shell.Windows();
                for (int i = 0; i < windows.Count; i++)
                {
                    dynamic window = windows.Item(i);
                    if (window != null && (IntPtr)window.HWND == hwnd)
                    {
                        string location = window.LocationURL;
                        if (string.IsNullOrEmpty(location)) continue;
                        return new Uri(location).LocalPath;
                    }
                }
            }
            catch 
            { 

            }
            return null;
        }
    }
}
