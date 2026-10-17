using System;
using System.IO;
using System.Runtime.InteropServices;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    /// Creates Windows virtual desktops and moves windows between them, through the VirtualDesktopAccessor.dll
    /// used by DeskMCP (Ciantic/VirtualDesktopAccessor). Desktop numbers exposed here are 1-based, as in Task View.
    /// The DLL is loaded from OMNI_VDA_PATH, or from %LOCALAPPDATA%\Omni\virtual-desktop-accessor\ by default.
    public sealed class VirtualDesktopService : IDisposable
    {
        public const string EnvironmentVariable = "OMNI_VDA_PATH";

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int GetCurrentDesktopNumberDelegate();
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int GetDesktopCountDelegate();
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int MoveWindowToDesktopNumberDelegate(IntPtr hwnd, int desktopNumber);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int CreateDesktopDelegate();
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int GetWindowDesktopNumberDelegate(IntPtr hwnd);

        private readonly IntPtr _library;
        private readonly GetCurrentDesktopNumberDelegate? _getCurrent;
        private readonly GetDesktopCountDelegate? _getCount;
        private readonly MoveWindowToDesktopNumberDelegate? _moveWindow;
        private readonly CreateDesktopDelegate? _createDesktop;
        private readonly GetWindowDesktopNumberDelegate? _getWindowDesktop;

        public VirtualDesktopService()
        {
            Path = ResolvePath();
            if (!File.Exists(Path) || !NativeLibrary.TryLoad(Path, out _library)) return;
            _getCurrent = Load<GetCurrentDesktopNumberDelegate>("GetCurrentDesktopNumber");
            _getCount = Load<GetDesktopCountDelegate>("GetDesktopCount");
            _moveWindow = Load<MoveWindowToDesktopNumberDelegate>("MoveWindowToDesktopNumber");
            _createDesktop = Load<CreateDesktopDelegate>("CreateDesktop");
            _getWindowDesktop = Load<GetWindowDesktopNumberDelegate>("GetWindowDesktopNumber");
        }

        public string Path { get; }

        public bool Available => _library != IntPtr.Zero && _getCurrent != null && _getCount != null
            && _moveWindow != null && _createDesktop != null && _getWindowDesktop != null;

        public int Count => Require(_getCount).Invoke();

        /// 1-based number of the desktop the user is currently viewing.
        public int Current => Require(_getCurrent).Invoke() + 1;

        /// Creates a desktop at the end of the list and returns its 1-based number.
        public int Create()
        {
            int native = Require(_createDesktop).Invoke();
            if (native < 0) throw new InvalidOperationException("Windows refused to create a virtual desktop.");
            return native + 1;
        }

        public void MoveWindow(IntPtr hwnd, int desktopNumber)
        {
            ValidateNumber(desktopNumber);
            if (Require(_moveWindow).Invoke(hwnd, desktopNumber - 1) != 1)
                throw new InvalidOperationException($"Could not move window {hwnd} to desktop {desktopNumber}. The window may belong to an elevated process.");
        }

        /// 1-based desktop number the window is on, or null when Windows does not know the window.
        public int? DesktopOf(IntPtr hwnd)
        {
            int native = Require(_getWindowDesktop).Invoke(hwnd);
            return native < 0 ? null : native + 1;
        }

        private void ValidateNumber(int desktopNumber)
        {
            int count = Count;
            if (desktopNumber < 1 || desktopNumber > count)
                throw new ArgumentException($"desktop_number must be between 1 and {count}.");
        }

        private static T Require<T>(T? value) where T : class =>
            value ?? throw new InvalidOperationException($"Virtual desktop support is not available. Put VirtualDesktopAccessor.dll at {ResolvePath()} (or set {EnvironmentVariable}); DeskMCP ships a copy in its virtual-desktop-accessor folder.");

        private T? Load<T>(string name) where T : Delegate
        {
            if (!NativeLibrary.TryGetExport(_library, name, out IntPtr export)) return null;
            return Marshal.GetDelegateForFunctionPointer<T>(export);
        }

        private static string ResolvePath()
        {
            string? configured = Environment.GetEnvironmentVariable(EnvironmentVariable);
            if (!string.IsNullOrWhiteSpace(configured)) return System.IO.Path.GetFullPath(configured.Trim());
            return System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Omni", "virtual-desktop-accessor", "VirtualDesktopAccessor.dll");
        }

        public void Dispose()
        {
            if (_library != IntPtr.Zero) NativeLibrary.Free(_library);
        }
    }
}
