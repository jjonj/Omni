using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Threading;
using OmniPower.Native;

namespace OmniPower
{
    /// Receives mouse and keyboard input through Raw Input on a hidden message-only window.
    /// RIDEV_INPUTSINK means input is delivered even when another app has focus. This sees the input that
    /// woke the PC more reliably than GetAsyncKeyState, which often misses it.
    public sealed class RawInputMonitor : IDisposable
    {
        private const string ClassName = "OmniPowerRawInput";
        private const ushort GenericDesktopPage = 0x01;
        private const ushort MouseUsage = 0x02;
        private const ushort KeyboardUsage = 0x06;

        private readonly Action<WakeInputEvent> _onEvent;
        private readonly WndProcDelegate _wndProc; // Kept in a field so the native side never calls a collected delegate.
        private readonly ManualResetEventSlim _ready = new ManualResetEventSlim(false);
        private Thread? _thread;
        private IntPtr _hwnd;
        private Exception? _startupError;

        public RawInputMonitor(Action<WakeInputEvent> onEvent)
        {
            _onEvent = onEvent;
            _wndProc = WndProc;
        }

        public void Start()
        {
            _thread = new Thread(ThreadMain) { IsBackground = true, Name = "OmniPower raw input" };
            _thread.Start();
            _ready.Wait();
            if (_startupError != null)
            {
                throw new InvalidOperationException("Raw input monitor failed to start: " + _startupError.Message, _startupError);
            }
        }

        private void ThreadMain()
        {
            try
            {
                IntPtr instance = NativeMethods.GetModuleHandle(null);
                var windowClass = new WNDCLASSEX
                {
                    cbSize = (uint)Marshal.SizeOf<WNDCLASSEX>(),
                    lpfnWndProc = _wndProc,
                    hInstance = instance,
                    lpszClassName = ClassName,
                };

                if (NativeMethods.RegisterClassEx(ref windowClass) == 0)
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }

                _hwnd = NativeMethods.CreateWindowEx(
                    0, ClassName, ClassName, 0, 0, 0, 0, 0,
                    NativeMethods.HWND_MESSAGE, IntPtr.Zero, instance, IntPtr.Zero);
                if (_hwnd == IntPtr.Zero)
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }

                var devices = new[]
                {
                    new RAWINPUTDEVICE { usUsagePage = GenericDesktopPage, usUsage = MouseUsage, dwFlags = NativeMethods.RIDEV_INPUTSINK, hwndTarget = _hwnd },
                    new RAWINPUTDEVICE { usUsagePage = GenericDesktopPage, usUsage = KeyboardUsage, dwFlags = NativeMethods.RIDEV_INPUTSINK, hwndTarget = _hwnd },
                };

                if (!NativeMethods.RegisterRawInputDevices(devices, (uint)devices.Length, (uint)Marshal.SizeOf<RAWINPUTDEVICE>()))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
            }
            catch (Exception ex)
            {
                _startupError = ex;
                _ready.Set();
                return;
            }

            _ready.Set();
            while (NativeMethods.GetMessage(out MSG message, IntPtr.Zero, 0, 0) > 0)
            {
                NativeMethods.DispatchMessage(ref message);
            }
        }

        private IntPtr WndProc(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam)
        {
            switch (message)
            {
                case NativeMethods.WM_INPUT:
                    HandleRawInput(lParam);
                    break;
                case NativeMethods.WM_CLOSE:
                    NativeMethods.DestroyWindow(hwnd);
                    return IntPtr.Zero;
                case NativeMethods.WM_DESTROY:
                    NativeMethods.PostQuitMessage(0);
                    return IntPtr.Zero;
            }

            return NativeMethods.DefWindowProc(hwnd, message, wParam, lParam);
        }

        private void HandleRawInput(IntPtr rawInputHandle)
        {
            try
            {
                uint headerSize = (uint)RawInputParser.HeaderSize(IntPtr.Size);
                uint size = 0;
                NativeMethods.GetRawInputData(rawInputHandle, NativeMethods.RID_INPUT, null, ref size, headerSize);
                if (size == 0)
                {
                    return;
                }

                var buffer = new byte[size];
                if (NativeMethods.GetRawInputData(rawInputHandle, NativeMethods.RID_INPUT, buffer, ref size, headerSize) != size)
                {
                    return;
                }

                WakeInputEvent? wakeEvent = RawInputParser.Parse(buffer, IntPtr.Size, DateTime.UtcNow);
                if (wakeEvent != null)
                {
                    _onEvent(wakeEvent);
                }
            }
            catch (Exception)
            {
                // Never let an exception escape into native callback code. A dropped event is safer than a crash.
            }
        }

        public void Dispose()
        {
            if (_hwnd != IntPtr.Zero)
            {
                NativeMethods.PostMessage(_hwnd, NativeMethods.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
                _hwnd = IntPtr.Zero;
            }

            _thread?.Join(2000);
            _ready.Dispose();
        }
    }
}
