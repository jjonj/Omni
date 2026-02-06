using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services
{
    public interface IMacroService
    {
        Task ExecuteMacroAsync(string script);
        Task ExecuteMacroAsync(MacroConfig macro);
    }

    public class MacroService : IMacroService
    {
        private readonly ProcessService _processService;
        private readonly InputService _inputService;
        private readonly ClipboardService _clipboardService;
        private readonly HubMonitorService _monitorService;

        public MacroService(
            ProcessService processService,
            InputService inputService,
            ClipboardService clipboardService,
            HubMonitorService monitorService)
        {
            _processService = processService;
            _inputService = inputService;
            _clipboardService = clipboardService;
            _monitorService = monitorService;
        }

        public async Task ExecuteMacroAsync(MacroConfig macro)
        {
            _monitorService.AddLogMessage($"[MacroService] Executing macro: {macro.Name}");
            await ExecuteMacroAsync(macro.Script);
        }

        public async Task ExecuteMacroAsync(string script)
        {
            if (string.IsNullOrWhiteSpace(script)) return;

            _monitorService.AddLogMessage($"[MacroService] Executing script...");
            var lines = script.Split(new char[] { '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries);
            
            foreach (var rawLine in lines)
            {
                var line = rawLine.Trim();
                if (string.IsNullOrEmpty(line) || line.StartsWith(";")) continue;

                var parts = line.Split(new char[] { ' ' }, 2);
                var command = parts[0].ToLower();
                var args = parts.Length > 1 ? parts[1].Trim() : "";

                try 
                {
                    switch (command)
                    {
                        case "send":
                            _inputService.SendKeys(NormalizeSendKeys(ExpandVariables(args)));
                            break;
                        case "sleep":
                            if (int.TryParse(args, out var ms)) await Task.Delay(ms);
                            break;
                        case "run":
                            {
                                var expanded = ExpandVariables(args);
                                var (exe, runArgs) = SplitCommand(expanded);
                                if (!string.IsNullOrWhiteSpace(exe))
                                {
                                    _processService.ShellExecute(exe, runArgs);
                                }
                            }
                            break;
                        case "runwait":
                            await _processService.ExecuteCommand(ExpandVariables(args));
                            break;
                        case "winactivate":
                            _processService.WinActivate(args);
                            break;
                        case "winclose":
                            _processService.WinClose(args);
                            break;
                        case "winminimize":
                            _processService.WinMinimize(args);
                            break;
                        case "winmaximize":
                            _processService.WinMaximize(args);
                            break;
                        case "winhide":
                            _processService.WinHide(args);
                            break;
                        case "waitwinactive":
                            var wwParts = args.Split(new char[] { ' ' }, 2);
                            var wwTitle = wwParts[0].Trim('"');
                            var wwTimeout = wwParts.Length > 1 && int.TryParse(wwParts[1], out var t) ? t : 5000;
                            _processService.WaitWinActive(wwTitle, wwTimeout);
                            break;
                        case "volup":
                            _inputService.SendKeyPress(0xAF);
                            break;
                        case "voldown":
                            _inputService.SendKeyPress(0xAE);
                            break;
                        case "volmute":
                            _inputService.SendKeyPress(0xAD);
                            break;
                        case "keydown":
                            break;
                        case "keyup":
                            break;
                        case "clipboard":
                            _clipboardService.SetClipboardText(ExpandVariables(args));
                            break;
                        case "powershell":
                        case "ps":
                            _processService.RunPowerShell(ExpandVariables(args));
                            break;
                        case "mousemoveabs":
                            var mmParts = args.Split(new char[] { ' ' });
                            if (mmParts.Length >= 2 && int.TryParse(mmParts[0], out var mx) && int.TryParse(mmParts[1], out var my))
                                _processService.MouseMoveAbs(mx, my);
                            break;
                        case "mouseclickat":
                            var mcParts = args.Split(new char[] { ' ' });
                            if (mcParts.Length >= 3 && int.TryParse(mcParts[1], out var cx) && int.TryParse(mcParts[2], out var cy))
                                _processService.MouseClickAt(mcParts[0], cx, cy);
                            break;
                    }
                }
                catch (Exception ex)
                {
                    _monitorService.AddLogMessage($"[MacroService] Error executing line '{line}': {ex.Message}");
                }
            }
        }

        private string ExpandVariables(string text)
        {
            if (string.IsNullOrEmpty(text)) return text;
            
            return text
                .Replace("{DATE}", DateTime.Now.ToString("yyyy-MM-dd"))
                .Replace("{TIME}", DateTime.Now.ToString("HH:mm:ss"))
                .Replace("{PC_UPTIME}", (DateTime.Now - Process.GetCurrentProcess().StartTime).ToString(@"dd\.hh\:mm\:ss"))
                .Replace("{ACTIVE_WINDOW_TITLE}", _inputService.GetActiveWindowTitle());
        }

        private static (string exe, string args) SplitCommand(string command)
        {
            if (string.IsNullOrWhiteSpace(command)) return ("", "");

            if (command.StartsWith("\""))
            {
                int nextQuote = command.IndexOf("\"", 1);
                if (nextQuote != -1)
                {
                    var exe = command.Substring(1, nextQuote - 1);
                    var args = command.Substring(nextQuote + 1).Trim();
                    return (exe, args);
                }
            }

            var parts = command.Split(' ', 2);
            var executable = parts[0];
            var arguments = parts.Length > 1 ? parts[1] : "";
            return (executable, arguments);
        }

        private static string NormalizeSendKeys(string keys)
        {
            if (string.IsNullOrWhiteSpace(keys)) return keys;

            // Support common (Key) style tokens.
            return keys
                .Replace("(Tab)", "{TAB}", StringComparison.OrdinalIgnoreCase)
                .Replace("(Enter)", "{ENTER}", StringComparison.OrdinalIgnoreCase)
                .Replace("(Esc)", "{ESC}", StringComparison.OrdinalIgnoreCase)
                .Replace("(Escape)", "{ESC}", StringComparison.OrdinalIgnoreCase);
        }
    }
}
