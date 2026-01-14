
using System;
using System.Drawing;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
using System.IO;
using System.Linq;

namespace OmniNPP
{
    public class CleanupForm : Form
    {
        private NppData nppData;
        private DataGridView grid;
        private List<TabInfo> tabs = new List<TabInfo>();
        
        private const int WM_USER = 0x400;
        private const int NPPMSG = WM_USER + 1000;
        
        private const int NPPM_GETNBOPENFILES = NPPMSG + 7;
        private const int NPPM_GETFULLPATHFROMBUFFERID = NPPMSG + 4;
        private const int NPPM_SWITCHTOFILE = NPPMSG + 10;
        private const int NPPM_MENUCOMMAND = NPPMSG + 48;
        private const int IDM_FILE_CLOSE = 41003;
        private const int NPPM_GETBUFFERIDS = NPPMSG + 8;
        private const int NPPM_GETCURRENTBUFFERID = NPPMSG + 6;
        private const int NPPM_GETBUFFERIDFROMPOS = NPPMSG + 11;
        
        private string configPath;
        private HashSet<string> alwaysKeepPaths = new HashSet<string>();
        private Action<string> log;

        [DllImport("user32.dll", EntryPoint = "SendMessageW", CharSet = CharSet.Unicode)]
        private static extern IntPtr SendMessage(IntPtr hWnd, int Msg, IntPtr wParam, IntPtr lParam);

        public CleanupForm(NppData data, Action<string> logger)
        {
            this.log = logger;
            this.nppData = data;
            this.configPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Notepad++", "plugins", "config", "OmniNPP.txt");
            LoadConfig();

            this.Text = "OmniNPP Cleanup";
            this.Size = new Size(900, 600);
            this.StartPosition = FormStartPosition.CenterParent;
            this.KeyPreview = true;
            this.BackColor = Color.FromArgb(45, 45, 48);
            this.ForeColor = Color.White;

            grid = new DataGridView();
            grid.Dock = DockStyle.Fill;
            grid.AutoGenerateColumns = false;
            grid.AllowUserToAddRows = false;
            grid.RowHeadersVisible = false;
            grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
            grid.MultiSelect = false;
            grid.BackgroundColor = Color.FromArgb(30, 30, 30);
            grid.ForeColor = Color.White;
            grid.DefaultCellStyle.BackColor = Color.FromArgb(30, 30, 30);
            grid.DefaultCellStyle.ForeColor = Color.White;
            grid.ColumnHeadersDefaultCellStyle.BackColor = Color.FromArgb(45, 45, 48);
            grid.ColumnHeadersDefaultCellStyle.ForeColor = Color.White;
            grid.EnableHeadersVisualStyles = false;
            grid.GridColor = Color.FromArgb(60, 60, 60);

            grid.Columns.Add(new DataGridViewCheckBoxColumn { Name = "Keep", HeaderText = "Keep", Width = 50 });
            grid.Columns.Add(new DataGridViewCheckBoxColumn { Name = "Always", HeaderText = "Always", Width = 60 });
            grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "File", HeaderText = "File", Width = 150, ReadOnly = true });
            grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Preview", HeaderText = "Content Preview", Width = 390, ReadOnly = true });
            grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Path", HeaderText = "Full Path", Width = 200, ReadOnly = true });

            this.Controls.Add(grid);

            var btnPanel = new Panel { Dock = DockStyle.Bottom, Height = 50, Padding = new Padding(10) };
            var lblHint = new Label { Text = "[Space] Toggle Keep | [A] Always | [Enter] Cleanup", AutoSize = true, Location = new Point(10, 15) };
            var btnCleanup = new Button { Text = "Decisive Cleanup", Dock = DockStyle.Right, Width = 150, FlatStyle = FlatStyle.Flat, BackColor = Color.FromArgb(0, 122, 204) };
            btnCleanup.Click += (s, e) => ExecuteCleanup();
            btnPanel.Controls.Add(lblHint);
            btnPanel.Controls.Add(btnCleanup);
            this.Controls.Add(btnPanel);

            LoadTabs();
            PopulateGrid();

            this.KeyDown += (s, e) => {
                if (e.KeyCode == Keys.Escape) this.Close();
                if (grid.CurrentRow != null) {
                    var tab = (TabInfo)grid.CurrentRow.Tag;
                    if (e.KeyCode == Keys.Space) {
                        tab.Keep = !tab.Keep;
                        grid.CurrentRow.Cells["Keep"].Value = tab.Keep;
                        e.Handled = true;
                    }
                    if (e.KeyCode == Keys.A) {
                        tab.AlwaysKeep = !tab.AlwaysKeep;
                        if (tab.AlwaysKeep) { tab.Keep = true; alwaysKeepPaths.Add(tab.Path); }
                        else alwaysKeepPaths.Remove(tab.Path);
                        grid.Rows[grid.CurrentRow.Index].Cells["Always"].Value = tab.AlwaysKeep;
                        grid.Rows[grid.CurrentRow.Index].Cells["Keep"].Value = tab.Keep;
                        e.Handled = true;
                    }
                }
                if (e.KeyCode == Keys.Enter) {
                    ExecuteCleanup();
                    e.Handled = true;
                }
            };
        }

        private void LoadConfig()
        {
            try {
                if (File.Exists(configPath)) {
                    foreach (var line in File.ReadAllLines(configPath)) {
                        if (!string.IsNullOrWhiteSpace(line)) alwaysKeepPaths.Add(line.Trim());
                    }
                }
            } catch {}
        }

        private void SaveConfig()
        {
            try {
                Directory.CreateDirectory(Path.GetDirectoryName(configPath));
                File.WriteAllLines(configPath, alwaysKeepPaths);
            } catch {}
        }

        private void LoadTabs()
        {
            log("--- LoadTabs (Manual Memory) ---");
            if (nppData._nppHandle == IntPtr.Zero) return;

            // 1. Get current buffer ID
            IntPtr currentId = SendMessage(nppData._nppHandle, NPPM_GETCURRENTBUFFERID, IntPtr.Zero, IntPtr.Zero);
            log("Current Buffer ID: " + currentId);

            // 2. Get all buffer IDs safely
            int totalFiles = (int)SendMessage(nppData._nppHandle, NPPM_GETNBOPENFILES, IntPtr.Zero, IntPtr.Zero);
            log("Total files: " + totalFiles);
            if (totalFiles <= 0) return;

            // Allocate a large enough buffer for IDs
            int maxIds = Math.Max(totalFiles, 100);
            IntPtr idBuffer = Marshal.AllocHGlobal(IntPtr.Size * maxIds);
            List<IntPtr> bufferIds = new List<IntPtr>();
            try {
                SendMessage(nppData._nppHandle, NPPM_GETBUFFERIDS, (IntPtr)maxIds, idBuffer);
                for (int i = 0; i < totalFiles; i++) {
                    IntPtr id = Marshal.ReadIntPtr(idBuffer, i * IntPtr.Size);
                    if (id != IntPtr.Zero) bufferIds.Add(id);
                }
            } finally {
                Marshal.FreeHGlobal(idBuffer);
            }
            log("IDs found via NPPM_GETBUFFERIDS: " + bufferIds.Count);

            // 3. Fallback to view enumeration if needed
            if (bufferIds.Count == 0) {
                log("Fallback to View enumeration...");
                for (int v = 0; v <= 1; v++) {
                    int count = (int)SendMessage(nppData._nppHandle, NPPM_GETNBOPENFILES, IntPtr.Zero, (IntPtr)(v + 1));
                    for (int i = 0; i < count; i++) {
                        IntPtr id = SendMessage(nppData._nppHandle, NPPM_GETBUFFERIDFROMPOS, (IntPtr)i, (IntPtr)v);
                        if (id != IntPtr.Zero) bufferIds.Add(id);
                    }
                }
                log("IDs found via View enumeration: " + bufferIds.Count);
            }

            // 4. Get paths for each ID
            IntPtr pathPtr = Marshal.AllocHGlobal(2048);
            try {
                foreach (var id in bufferIds) {
                    // Clear buffer
                    for (int j = 0; j < 1024; j++) Marshal.WriteInt16(pathPtr, j * 2, 0);
                    
                    SendMessage(nppData._nppHandle, NPPM_GETFULLPATHFROMBUFFERID, id, pathPtr);
                    string path = Marshal.PtrToStringUni(pathPtr);
                    
                    if (!string.IsNullOrEmpty(path)) {
                        log("  Found Tab: " + Path.GetFileName(path));
                        bool always = alwaysKeepPaths.Contains(path);
                        tabs.Add(new TabInfo {
                            BufferId = id,
                            Path = path,
                            FileName = Path.GetFileName(path),
                            Keep = always,
                            AlwaysKeep = always,
                            Preview = "(No Preview)"
                        });
                    }
                }
            } finally {
                Marshal.FreeHGlobal(pathPtr);
            }

            log("Total tabs loaded: " + tabs.Count);
        }

        private void PopulateGrid()
        {
            grid.Rows.Clear();
            foreach (var tab in tabs)
            {
                int rowIndex = grid.Rows.Add(tab.Keep, tab.AlwaysKeep, tab.FileName, tab.Preview, tab.Path);
                grid.Rows[rowIndex].Tag = tab;
            }
        }

        private void ExecuteCleanup()
        {
            SaveConfig();
            var toClose = tabs.Where(t => !t.Keep).ToList();
            if (toClose.Count == 0) { this.Close(); return; }
            if (MessageBox.Show("Close " + toClose.Count + " tabs?", "OmniNPP", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
            foreach (var tab in toClose) {
                SendMessage(nppData._nppHandle, NPPM_SWITCHTOFILE, IntPtr.Zero, tab.BufferId);
                SendMessage(nppData._nppHandle, NPPM_MENUCOMMAND, (IntPtr)IDM_FILE_CLOSE, IntPtr.Zero);
            }
            this.Close();
        }
    }

    public class TabInfo {
        public IntPtr BufferId { get; set; }
        public string FileName { get; set; } = "";
        public string Path { get; set; } = "";
        public string Preview { get; set; } = "";
        public bool Keep { get; set; }
        public bool AlwaysKeep { get; set; }
    }
}
