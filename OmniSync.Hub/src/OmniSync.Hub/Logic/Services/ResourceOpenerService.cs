using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
    public class ResourceOpenerService
    {
        private readonly ProcessService _processService;
        private readonly HubSettingsService _settingsService;

        public ResourceOpenerService(ProcessService processService, HubSettingsService settingsService)
        {
            _processService = processService;
            _settingsService = settingsService;
        }

        public async Task OpenResource(string path, int? lineNumber = null)
        {
            if (string.IsNullOrWhiteSpace(path)) return;

            string target = path.Trim();
            
            // 1. Check for URLs or HTML files (use Chrome mapping)
            if (IsWebResource(target))
            {
                var chromePath = _settingsService.GetPath("chrome");
                if (!string.IsNullOrEmpty(chromePath))
                {
                    await _processService.ExecuteCommand($"\"{chromePath}\" \"{target}\"");
                    return;
                }
            }

            // 2. Check for specialized Notepad++ logic (for code files)
            if (IsCodeFile(target))
            {
                // Default Notepad++ path if not mapped, or could use a mapping
                string nppPath = @"C:\Program Files\Notepad++\notepad++.exe";
                string args = lineNumber.HasValue ? $"-n{lineNumber} " : "";
                await _processService.ExecuteCommand($"\"{nppPath}\" {args}\"{target}\"");
                return;
            }

            // 3. Default: Use ProcessService to open via shell
            // ShellExecute uses Process.Start with UseShellExecute = true
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
