using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class ClipboardService
    {
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool OpenClipboard(IntPtr hWndNewOwner);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool CloseClipboard();

        [DllImport("user32.dll")]
        private static extern IntPtr GetClipboardData(uint uFormat);

        [DllImport("user32.dll")]
        private static extern bool IsClipboardFormatAvailable(uint format);

        private const uint CF_UNICODETEXT = 13;

        public event EventHandler<string> ClipboardTextChanged;

        private Thread _clipboardThread;
        private CancellationTokenSource _cancellationTokenSource;

        public void Start()
        {
            _cancellationTokenSource = new CancellationTokenSource();
            _clipboardThread = new Thread(() => MonitorClipboard(_cancellationTokenSource.Token));
            _clipboardThread.SetApartmentState(ApartmentState.STA);
            _clipboardThread.Start();
        }

        public void Stop()
        {
            _cancellationTokenSource?.Cancel();
            _clipboardThread?.Join();
        }

        private void MonitorClipboard(CancellationToken token)
        {
            string lastClipboardText = GetClipboardText();

            while (!token.IsCancellationRequested)
            {
                Thread.Sleep(500); // Check every 500ms
                string currentClipboardText = GetClipboardText();
                if (currentClipboardText != lastClipboardText)
                {
                    lastClipboardText = currentClipboardText;
                    ClipboardTextChanged?.Invoke(this, currentClipboardText);
                }
            }
        }

        [DllImport("kernel32.dll")]
        static extern IntPtr GlobalLock(IntPtr hMem);

        [DllImport("kernel32.dll")]
        static extern bool GlobalUnlock(IntPtr hMem);

        [DllImport("user32.dll")]
        private static extern bool EmptyClipboard();

        [DllImport("user32.dll")]
        private static extern IntPtr SetClipboardData(uint uFormat, IntPtr hMem);

        public void SetClipboardText(string text)
        {
            if (!OpenClipboard(IntPtr.Zero))
                return;

            EmptyClipboard();

            IntPtr hGlobal = IntPtr.Zero;
            try
            {
                hGlobal = Marshal.StringToHGlobalUni(text);
                SetClipboardData(CF_UNICODETEXT, hGlobal);
            }
            finally
            {
                if (hGlobal != IntPtr.Zero)
                    Marshal.FreeHGlobal(hGlobal);

                CloseClipboard();
            }
        }

        private string GetClipboardText()
        {
            if (!IsClipboardFormatAvailable(CF_UNICODETEXT))
                return null;

            if (!OpenClipboard(IntPtr.Zero))
                return null;

            IntPtr handle = GetClipboardData(CF_UNICODETEXT);
            if (handle == IntPtr.Zero)
            {
                CloseClipboard();
                return null;
            }

            IntPtr pointer = IntPtr.Zero;
            try
            {
                pointer = GlobalLock(handle);
                if (pointer == IntPtr.Zero)
                {
                    CloseClipboard();
                    return null;
                }

                string data = Marshal.PtrToStringUni(pointer);
                return data;
            }
            finally
            {
                if (pointer != IntPtr.Zero)
                    GlobalUnlock(handle);

                CloseClipboard();
            }
        }
    }
}
