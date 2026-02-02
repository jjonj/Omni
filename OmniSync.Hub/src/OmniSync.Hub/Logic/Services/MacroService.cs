using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text.Json;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services
{
    public interface IMacroService
    {
        Task ExecuteMacroAsync(JsonElement commands);
        Task ExecuteMacroAsync(MacroConfig macro);
        Task ExecuteMacroAsync(string script);
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

        public async Task ExecuteMacroAsync(string script)
        {
            _monitorService.AddLogMessage($"[MacroService] Executing raw script: {script.Take(20)}...");
            var lines = script.Split(new[] { '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (var line in lines)
            {
                var parts = line.Split(new[] { ' ' }, 2);
                var type = parts[0].ToLower();
                var arg = parts.Length > 1 ? parts[1].Trim() : "";

                switch (type)
                {
                    case "send":
                        _inputService.SendKeys(ExpandVariables(arg));
                        break;
                    case "sleep":
                        if (int.TryParse(arg, out var ms)) await Task.Delay(ms);
                        break;
                    case "run":
                        await _processService.ExecuteCommand(ExpandVariables(arg));
                        break;
                    case "winactivate":
                        _processService.WinActivate(arg);
                        break;
                    case "winclose":
                        _processService.WinClose(arg);
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
                    case "clipboard":
                        _clipboardService.SetClipboardText(ExpandVariables(arg));
                        break;
                    case "powershell":
                        _processService.RunPowerShell(ExpandVariables(arg));
                        break;
                    // Android-only or unknown actions are skipped per user request
                }
            }
        }

        public async Task ExecuteMacroAsync(JsonElement commands)
        {
            _monitorService.AddLogMessage("[MacroService] Executing macro from JSON.");
            await ExecuteCommandsAsync(commands);
        }

        public async Task ExecuteMacroAsync(MacroConfig macro)
        {
            _monitorService.AddLogMessage($"[MacroService] Executing macro: {macro.Name}");
            var json = JsonSerializer.Serialize(macro.Commands);
            using var doc = JsonDocument.Parse(json);
            await ExecuteCommandsAsync(doc.RootElement);
        }

        private async Task ExecuteCommandsAsync(JsonElement commands)
        {
            foreach (var cmd in commands.EnumerateArray())
            {
                var type = cmd.TryGetProperty("type", out var typeProp) ? typeProp.GetString() : 
                           cmd.TryGetProperty("Type", out var typeProp2) ? typeProp2.GetString() : null;

                switch (type?.ToLower())
                {
                    case "send":
                        var keys = ExpandVariables(GetStringProperty(cmd, "keys") ?? "");
                        _inputService.SendKeys(keys);
                        break;
                    case "sleep":
                        var ms = GetLongProperty(cmd, "durationMs") ?? 0;
                        if (ms > 0) await Task.Delay((int)ms);
                        break;
                    case "run":
                        var path = ExpandVariables(GetStringProperty(cmd, "path") ?? "");
                        await _processService.ExecuteCommand(path);
                        break;
                    case "winactivate":
                        _processService.WinActivate(GetStringProperty(cmd, "title") ?? "");
                        break;
                    case "winclose":
                        _processService.WinClose(GetStringProperty(cmd, "title") ?? "");
                        break;
                    case "winminimize":
                        _processService.WinMinimize(GetStringProperty(cmd, "title") ?? "");
                        break;
                    case "winmaximize":
                        _processService.WinMaximize(GetStringProperty(cmd, "title") ?? "");
                        break;
                    case "winhide":
                        _processService.WinHide(GetStringProperty(cmd, "title") ?? "");
                        break;
                    case "waitwinactive":
                        var target = GetStringProperty(cmd, "title") ?? "";
                        var timeout = GetIntProperty(cmd, "timeoutMs") ?? 5000;
                        _processService.WaitWinActive(target, timeout);
                        break;
                    case "volup":
                        _inputService.SendKeyPress(0xAF); // VK_VOLUME_UP
                        break;
                    case "voldown":
                        _inputService.SendKeyPress(0xAE); // VK_VOLUME_DOWN
                        break;
                    case "volmute":
                        _inputService.SendKeyPress(0xAD); // VK_VOLUME_MUTE
                        break;
                    case "clipboard":
                        var text = ExpandVariables(GetStringProperty(cmd, "text") ?? "");
                        _clipboardService.SetClipboardText(text);
                        break;
                    case "powershell":
                        var code = ExpandVariables(GetStringProperty(cmd, "code") ?? "");
                        _processService.RunPowerShell(code);
                        break;
                    case "mousemoveabs":
                        _processService.MouseMoveAbs(GetIntProperty(cmd, "x") ?? 0, GetIntProperty(cmd, "y") ?? 0);
                        break;
                    case "mouseclickat":
                        _processService.MouseClickAt(GetStringProperty(cmd, "button") ?? "left", GetIntProperty(cmd, "x") ?? 0, GetIntProperty(cmd, "y") ?? 0);
                        break;
                }
            }
        }

        private string? GetStringProperty(JsonElement element, string name)
        {
            if (element.TryGetProperty(name, out var prop)) return prop.GetString();
            // Try PascalCase as well for C# serialized objects
            string pascalName = char.ToUpper(name[0]) + name.Substring(1);
            if (element.TryGetProperty(pascalName, out var prop2)) return prop2.GetString();
            return null;
        }

        private int? GetIntProperty(JsonElement element, string name)
        {
            if (element.TryGetProperty(name, out var prop)) 
            {
                if (prop.ValueKind == JsonValueKind.Number) return prop.GetInt32();
                if (prop.ValueKind == JsonValueKind.String && int.TryParse(prop.GetString(), out var result)) return result;
            }
            string pascalName = char.ToUpper(name[0]) + name.Substring(1);
            if (element.TryGetProperty(pascalName, out var prop2)) 
            {
                if (prop2.ValueKind == JsonValueKind.Number) return prop2.GetInt32();
                if (prop2.ValueKind == JsonValueKind.String && int.TryParse(prop2.GetString(), out var result)) return result;
            }
            return null;
        }

        private long? GetLongProperty(JsonElement element, string name)
        {
            if (element.TryGetProperty(name, out var prop)) 
            {
                if (prop.ValueKind == JsonValueKind.Number) return prop.GetInt64();
                if (prop.ValueKind == JsonValueKind.String && long.TryParse(prop.GetString(), out var result)) return result;
            }
            string pascalName = char.ToUpper(name[0]) + name.Substring(1);
            if (element.TryGetProperty(pascalName, out var prop2)) 
            {
                if (prop2.ValueKind == JsonValueKind.Number) return prop2.GetInt64();
                if (prop2.ValueKind == JsonValueKind.String && long.TryParse(prop2.GetString(), out var result)) return result;
            }
            return null;
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
    }
}
