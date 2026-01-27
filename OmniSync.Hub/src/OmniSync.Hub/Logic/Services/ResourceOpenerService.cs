using System.Threading.Tasks;
using System.Linq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services
{
    public class ResourceOpenerService
    {
        private readonly ProcessService _processService;
        private readonly HubSettingsService _settingsService;
        private readonly HubMonitorService _monitorService;

        public ResourceOpenerService(ProcessService processService, HubSettingsService settingsService, HubMonitorService monitorService)
        {
            _processService = processService;
            _settingsService = settingsService;
            _monitorService = monitorService;
        }

        public async Task OpenResource(string path, int? lineNumber = null)
        {
            if (string.IsNullOrWhiteSpace(path)) 
            {
                _monitorService.AddLogMessage("[ResourceOpener] OpenResource called with null/empty path.");
                return;
            }

            string target = path.Trim();
            _monitorService.AddLogMessage($"[ResourceOpener] Opening resource: '{target}' (Line: {lineNumber})");
            
            // 1. Check for URLs or HTML files (use Chrome mapping)
            if (IsWebResource(target))
            {
                var chromePath = _settingsService.GetPath("chrome");
                _monitorService.AddLogMessage($"[ResourceOpener] Web resource detected. Chrome mapping: '{chromePath}'");
                
                if (string.IsNullOrEmpty(chromePath))
                {
                    chromePath = @"C:\Program Files\Google\Chrome\Application\chrome.exe";
                }

                // If mapping contains arguments (like vivaldi profile), we need to split them
                if (chromePath.Contains(" --"))
                {
                    int splitIdx = chromePath.IndexOf(" --");
                    string exe = chromePath.Substring(0, splitIdx).Trim('\"');
                    string extraArgs = chromePath.Substring(splitIdx).Trim();
                    _processService.ShellExecute(exe, $"{extraArgs} \"{target}\"");
                }
                else
                {
                    _processService.ShellExecute(chromePath, $"\"{target}\"");
                }
                return;
            }

            // 2. Check for specialized Notepad++ logic (for code files)
            if (IsCodeFile(target))
            {
                // Default Notepad++ path if not mapped, or use the mapped 'npp' path
                string nppPath = _settingsService.GetPath("npp") ?? @"C:\Program Files\Notepad++\notepad++.exe";
                string args = lineNumber.HasValue ? $"-n{lineNumber} " : "";
                _monitorService.AddLogMessage($"[ResourceOpener] Code file detected. Using NPP: '{nppPath}' Args: '{args}'");
                
                // Use ShellExecute directly for NPP - this was the key fix!
                _processService.ShellExecute(nppPath, $"{args}\"{target}\"");
                return;
            }

            // 3. Default: Use ProcessService to open via shell
            // ShellExecute uses Process.Start with UseShellExecute = true
            _monitorService.AddLogMessage($"[ResourceOpener] Defaulting to ShellExecute for: '{target}'");
            _processService.ShellExecute(target);
        }

        private bool IsWebResource(string path)
        {
            return path.StartsWith("http://", System.StringComparison.OrdinalIgnoreCase) || 
                   path.StartsWith("https://", System.StringComparison.OrdinalIgnoreCase) ||
                   path.EndsWith(".html", System.StringComparison.OrdinalIgnoreCase) ||
                   path.EndsWith(".htm", System.StringComparison.OrdinalIgnoreCase);
        }

        private bool IsCodeFile(string path)
        {
            string ext = System.IO.Path.GetExtension(path).ToLower();
            string[] codeExts = { ".cs", ".js", ".ts", ".py", ".cpp", ".h", ".java", ".md", ".txt", ".json", ".xml", ".css", ".html" };
            return codeExts.Contains(ext);
        }
    }
}
