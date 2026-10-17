param([long]$Hwnd = 24448820)
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class NativeWindowChildren
{
    [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr state);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassNameW(IntPtr hwnd, StringBuilder sb, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowTextW(IntPtr hwnd, StringBuilder sb, int max);
    delegate bool EnumProc(IntPtr hwnd, IntPtr state);
    public static string[] List(long parent) {
        var result = new List<string>();
        EnumWindowsProc(new IntPtr(parent), result);
        return result.ToArray();
    }
    static void EnumWindowsProc(IntPtr parent, List<string> result) {
        EnumChildWindows(parent, (hwnd, _) => {
            var clazz = new StringBuilder(256); GetClassNameW(hwnd, clazz, clazz.Capacity);
            var text = new StringBuilder(256); GetWindowTextW(hwnd, text, text.Capacity);
            result.Add(hwnd.ToInt64() + " " + clazz + " " + text);
            return true;
        }, IntPtr.Zero);
    }
}
"@
[NativeWindowChildren]::List($Hwnd)
