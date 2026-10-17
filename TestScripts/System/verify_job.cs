using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System.Diagnostics;

class VerifyJob
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr OpenJobObject(uint dwDesiredAccess, bool bInheritHandle, string lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool QueryInformationJobObject(IntPtr hJob, int JobObjectInfoClass, IntPtr lpJobObjectInfo, uint cbJobObjectInfoLength, out uint lpReturnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool CloseHandle(IntPtr hObject);

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_BASIC_PROCESS_ID_LIST
    {
        public uint NumberOfAssignedProcesses;
        public uint NumberOfProcessIdsInList;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 100)]
        public UIntPtr[] ProcessIdList;
    }

    const uint JOB_OBJECT_QUERY = 0x0004;
    const int JobObjectBasicProcessIdList = 3;

    static void Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.WriteLine("Searching for all active GeminiJob_ Job Objects...");
            for (int i = 1; i < 100000; i++)
            {
                // We don't know the PIDs, so we'll just check all processes to see if they have an associated job?
                // No, the Hub will know the RootPID from discovery.
            }
            
            // Let's just look at running processes and try to open a job with their ID
            foreach (var p in Process.GetProcessesByName("gmi_job_test"))
            {
                CheckJob(p.Id);
            }
            foreach (var p in Process.GetProcessesByName("gmi"))
            {
                CheckJob(p.Id);
            }
            return;
        }

        int pid = int.Parse(args[0]);
        CheckJob(pid);
    }

    static void CheckJob(int pid)
    {
        string jobName = "GeminiJob_" + pid;
        IntPtr hJob = OpenJobObject(JOB_OBJECT_QUERY, false, jobName);
        
        if (hJob == IntPtr.Zero)
        {
            Console.WriteLine("[-] Job Object NOT FOUND: " + jobName);
            return;
        }

        Console.WriteLine("[+] Job Object FOUND: " + jobName);

        uint size = (uint)Marshal.SizeOf(typeof(JOBOBJECT_BASIC_PROCESS_ID_LIST));
        IntPtr ptr = Marshal.AllocHGlobal((int)size);
        
        uint returnLength = 0;
        if (QueryInformationJobObject(hJob, JobObjectBasicProcessIdList, ptr, size, out returnLength))
        {
            var info = (JOBOBJECT_BASIC_PROCESS_ID_LIST)Marshal.PtrToStructure(ptr, typeof(JOBOBJECT_BASIC_PROCESS_ID_LIST));
            Console.WriteLine("    Processes in Job: " + info.NumberOfAssignedProcesses);
            for (int i = 0; i < info.NumberOfProcessIdsInList; i++)
            {
                int pId = (int)info.ProcessIdList[i];
                string name = "Unknown";
                try { name = Process.GetProcessById(pId).ProcessName; } catch { }
                Console.WriteLine("    - PID: " + pId + " (" + name + ")");
            }
        }
        else
        {
            Console.WriteLine("    Failed to query process list. Error: " + Marshal.GetLastWin32Error());
        }

        Marshal.FreeHGlobal(ptr);
        CloseHandle(hJob);
    }
}
