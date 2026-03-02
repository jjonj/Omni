using System.Reflection;
using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using Xunit;

namespace OmniSync.Hub.Tests.Services
{
    public class AiBridgeSignalRContractTests
    {
        [Fact]
        public void RpcApiHub_ImplementsRequiredClientToHubMethods_FromContract()
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(FindContractPath()));
            var methods = doc.RootElement
                .GetProperty("signalr")
                .GetProperty("clientToHubMethods")
                .EnumerateArray()
                .Select(x => x.GetString())
                .Where(x => !string.IsNullOrWhiteSpace(x))
                .Cast<string>()
                .ToList();

            var hubMethods = typeof(RpcApiHub)
                .GetMethods(BindingFlags.Instance | BindingFlags.Public)
                .Select(m =>
                {
                    var attr = m.GetCustomAttribute<HubMethodNameAttribute>();
                    return new
                    {
                        MethodName = m.Name,
                        HubName = attr?.Name
                    };
                })
                .ToList();

            foreach (var required in methods)
            {
                bool found = hubMethods.Any(m =>
                    string.Equals(m.MethodName, required, StringComparison.Ordinal) ||
                    string.Equals(m.HubName, required, StringComparison.Ordinal));

                Assert.True(found, $"Required Hub method '{required}' is missing from RpcApiHub.");
            }
        }

        private static string FindContractPath()
        {
            string current = AppContext.BaseDirectory;
            for (int i = 0; i < 15; i++)
            {
                string[] candidates =
                {
                    Path.Combine(current, "TestScripts", "AIFeature", "Contracts", "omni-ai-bridge-v1.json"),
                    Path.Combine(current, "Contracts", "omni-ai-bridge-v1.json"),
                };

                foreach (var candidate in candidates)
                {
                    if (File.Exists(candidate))
                    {
                        return candidate;
                    }
                }

                var parent = Directory.GetParent(current);
                if (parent == null)
                {
                    break;
                }
                current = parent.FullName;
            }

            throw new FileNotFoundException("Could not locate omni-ai-bridge-v1.json");
        }
    }
}
