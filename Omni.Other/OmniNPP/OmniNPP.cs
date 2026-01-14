
using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Text;
using System.IO;

namespace OmniNPP
{
    public static class Plugin
    {
        public const string PluginName = "OmniNPP";
        static NppData nppData;
        static List<FuncItem> funcItems = new List<FuncItem>();
        static IntPtr funcItemsPtr = IntPtr.Zero;
        private static string logPath = @"D:\SSDProjects\Omni\Omni.Other\OmniNPP\OmniNPP_debug.log";

        public static void Log(string msg)
        {
            try {
                File.AppendAllText(logPath, "[" + DateTime.Now.ToString("HH:mm:ss") + "] " + msg + "\r\n");
            } catch {}
        }

        [UnmanagedCallersOnly(EntryPoint = "setInfo")]
        public static unsafe void SetInfo(NppData* data)
        {
            if (data != null)
            {
                nppData = *data;
                Log("SetInfo called. NPP Handle: " + nppData._nppHandle);
            }
        }

        [UnmanagedCallersOnly(EntryPoint = "getName")]
        public static IntPtr GetName()
        {
            return Marshal.StringToHGlobalUni(PluginName);
        }

        [UnmanagedCallersOnly(EntryPoint = "isUnicode")]
        public static bool IsUnicode()
        {
            return true;
        }

        [UnmanagedCallersOnly(EntryPoint = "getFuncsArray")]
        public static unsafe IntPtr GetFuncsArray(IntPtr nbFunc)
        {
            Log("getFuncsArray called");
            if (funcItems.Count == 0)
            {
                AddFuncItem("Run Tab Cleanup", &RunCleanup);
            }

            Marshal.WriteInt32(nbFunc, funcItems.Count);
            
            if (funcItemsPtr != IntPtr.Zero) Marshal.FreeHGlobal(funcItemsPtr);
            
            int size = Marshal.SizeOf<FuncItem>();
            funcItemsPtr = Marshal.AllocHGlobal(size * funcItems.Count);
            
            for (int i = 0; i < funcItems.Count; i++)
            {
                IntPtr itemPtr = new IntPtr(funcItemsPtr.ToInt64() + (i * size));
                Marshal.StructureToPtr(funcItems[i], itemPtr, false);
            }
            
            return funcItemsPtr;
        }

        static unsafe void AddFuncItem(string name, delegate* unmanaged<void> func)
        {
            FuncItem item = new FuncItem();
            char[] nameChars = name.ToCharArray();
            int len = Math.Min(63, nameChars.Length);
            for (int i = 0; i < len; i++)
                item._itemName[i] = nameChars[i];
            item._itemName[len] = (char)0;
            
            item._pFunc = (IntPtr)func;
            item._cmdID = funcItems.Count;
            item._init2Check = false;
            item._pShKey = IntPtr.Zero;
            funcItems.Add(item);
        }

        [UnmanagedCallersOnly]
        public static void RunCleanup()
        {
            Log("RunCleanup requested");
            try {
                Application.EnableVisualStyles();
                using (var form = new CleanupForm(nppData, Log))
                {
                    form.ShowDialog();
                }
            } catch (Exception ex) {
                Log("ERROR in RunCleanup: " + ex.ToString());
                MessageBox.Show(ex.ToString(), "OmniNPP Error");
            }
        }

        [UnmanagedCallersOnly(EntryPoint = "beNotified")]
        public static void BeNotified(IntPtr notifyCode)
        {
        }

        [UnmanagedCallersOnly(EntryPoint = "messageProc")]
        public static IntPtr MessageProc(uint Message, IntPtr wParam, IntPtr lParam)
        {
            return new IntPtr(1);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct NppData
    {
        public IntPtr _nppHandle;
        public IntPtr _scintillaMainHandle;
        public IntPtr _scintillaSecondHandle;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public unsafe struct FuncItem
    {
        public fixed char _itemName[64];
        public IntPtr _pFunc;
        public int _cmdID;
        public bool _init2Check;
        public IntPtr _pShKey;
    }
}
