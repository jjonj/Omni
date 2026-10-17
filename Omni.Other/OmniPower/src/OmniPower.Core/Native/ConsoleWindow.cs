using System;
using System.IO;
using System.Text;
using Microsoft.Win32.SafeHandles;
using OmniPower.Native;

namespace OmniPower
{
    /// Gives a WinExe launcher a console window for the input test. Normal launches stay windowless.
    public static class ConsoleWindow
    {
        public static void Attach()
        {
            NativeMethods.AllocConsole();

            // A WinExe has no standard handles, and AllocConsole doesn't fill them in. Open the console
            // devices directly, otherwise Console.WriteLine goes nowhere and the window stays blank.
            var output = OpenConsoleDevice("CONOUT$", NativeMethods.GENERIC_READ | NativeMethods.GENERIC_WRITE);
            var input = OpenConsoleDevice("CONIN$", NativeMethods.GENERIC_READ | NativeMethods.GENERIC_WRITE);
            Console.SetOut(new StreamWriter(output, new UTF8Encoding(false)) { AutoFlush = true });
            Console.SetIn(new StreamReader(input, Encoding.UTF8));
            DisableQuickEdit(input.SafeFileHandle.DangerousGetHandle());
        }

        // Quick Edit mode freezes all output while text is selected, so a stray click looks like a hang.
        private static void DisableQuickEdit(IntPtr inputHandle)
        {
            const uint EnableQuickEditMode = 0x0040;
            const uint EnableExtendedFlags = 0x0080;
            if (NativeMethods.GetConsoleMode(inputHandle, out uint mode))
            {
                NativeMethods.SetConsoleMode(inputHandle, (mode & ~EnableQuickEditMode) | EnableExtendedFlags);
            }
        }

        private static FileStream OpenConsoleDevice(string name, uint access)
        {
            SafeFileHandle handle = NativeMethods.CreateFile(
                name, access, NativeMethods.FILE_SHARE_READ | NativeMethods.FILE_SHARE_WRITE,
                IntPtr.Zero, NativeMethods.OPEN_EXISTING, 0, IntPtr.Zero);
            if (handle.IsInvalid)
            {
                throw new IOException($"Could not open {name}.");
            }

            return new FileStream(handle, FileAccess.ReadWrite);
        }
    }
}
