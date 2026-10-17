using System.Text;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class MacroCommandProvider : IAssistantCommandProvider
{
    private static readonly string[] LeadIns = { "run ", "activate ", "start ", "do " };
    private readonly GoogleHomeService _googleHome;
    private readonly IMacroService _macroService;

    public MacroCommandProvider(
        GoogleHomeService googleHome,
        IMacroService macroService)
    {
        _googleHome = googleHome;
        _macroService = macroService;
    }

    public string Name => "voice-macros";

    public IReadOnlyList<AssistantCommandRejection> Rejected => Array.Empty<AssistantCommandRejection>();

    public IReadOnlyList<IAssistantCommand> GetCommands()
        => _googleHome.GetVoiceMacros()
            .Where(macro => !string.IsNullOrWhiteSpace(macro.Name))
            .Select(macro => (IAssistantCommand)new MacroAssistantCommand(
                macro.Name.Trim(),
                macro.Script,
                _macroService))
            .ToList();

    private sealed class MacroAssistantCommand : IAssistantCommand
    {
        private readonly string _name;
        private readonly string _script;
        private readonly IMacroService _macroService;

        public MacroAssistantCommand(
            string name,
            string script,
            IMacroService macroService)
        {
            _name = name;
            _script = script;
            _macroService = macroService;
            var slug = Slug(name);
            Descriptor = new AssistantCommandDescriptor(
                $"routine.{slug}",
                1,
                $"Run the saved '{name}' Omni macro.",
                AssistantExecutionHost.Desktop,
                Array.Empty<AssistantArgumentSpec>(),
                new[]
                {
                    new AssistantCommandExample(
                        $"activate {name}",
                        new Dictionary<string, string>())
                },
                AssistantTimingSupport.RelativeTime,
                new AssistantStalenessPolicy(TimeSpan.FromMinutes(10)),
                new AssistantRiskPolicy(
                    AssistantRiskLevel.Medium,
                    AssistantTrustLevel.Normal),
                new AssistantExecutionPolicy(
                    TimeSpan.FromMinutes(5),
                    $"routine:{slug}",
                    Idempotent: false));
        }

        public AssistantCommandDescriptor Descriptor { get; }

        public AssistantParseResult? TryParse(AssistantParseInput input)
        {
            var text = input.Text.Trim().TrimEnd('.', '!');
            foreach (var lead in LeadIns)
            {
                if (text.StartsWith(lead, StringComparison.OrdinalIgnoreCase))
                {
                    text = text[lead.Length..].Trim();
                    break;
                }
            }

            if (!string.Equals(text, _name, StringComparison.OrdinalIgnoreCase))
                return null;

            return new AssistantParseResult(
                new Dictionary<string, System.Text.Json.JsonElement>(),
                1000 + _name.Length);
        }

        public async Task<AssistantCommandOutcome> ExecuteAsync(
            AssistantCommandInvocation invocation,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await _macroService.ExecuteMacroAsync(_script);
            return new AssistantCommandOutcome.Succeeded($"Ran macro {_name}.");
        }

        private static string Slug(string name)
        {
            var builder = new StringBuilder();
            foreach (var ch in name.ToLowerInvariant())
            {
                if (char.IsLetter(ch))
                {
                    builder.Append(ch);
                }
                else if (char.IsDigit(ch))
                {
                    builder.Append(ch switch
                    {
                        '0' => "zero",
                        '1' => "one",
                        '2' => "two",
                        '3' => "three",
                        '4' => "four",
                        '5' => "five",
                        '6' => "six",
                        '7' => "seven",
                        '8' => "eight",
                        _ => "nine"
                    });
                }
                else if (builder.Length > 0 && builder[^1] != '_')
                {
                    builder.Append('_');
                }
            }

            var slug = builder.ToString().Trim('_');
            return slug.Length == 0 ? "macro" : slug;
        }
    }
}
