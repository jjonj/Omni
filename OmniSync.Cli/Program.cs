using Microsoft.AspNetCore.SignalR.Client;
using System;
using System.Threading.Tasks;

namespace OmniSync.Cli
{
    class Program
    {
        private static string _hubUrl = "http://localhost:5000/signalrhub"; // Default Hub URL
        private static string _apiKey = "test_api_key"; // Placeholder API Key

        static async Task Main(string[] args)
        {
            if (args.Length < 1)
            {
                Console.WriteLine("Usage: OmniSync.Cli <command_to_execute> [hub_url] [api_key]");
                Console.WriteLine("Example: OmniSync.Cli \"dir C:\\\" http://localhost:5000/signalrhub MY_API_KEY");
                return;
            }

            string commandToExecute = args[0];

            if (args.Length > 1)
            {
                _hubUrl = args[1];
            }
            if (args.Length > 2)
            {
                _apiKey = args[2];
            }

            var connection = new HubConnectionBuilder()
                .WithUrl(_hubUrl)
                .Build();

            connection.On<string>("ReceiveCommandOutput", (output) =>
            {
                Console.Write(output);
            });

            connection.Closed += async (error) =>
            {
                Console.WriteLine($"Connection closed due to an error: {error?.Message}");
                await Task.Delay(5000);
                // Optionally, try to reconnect here
            };

            try
            {
                await connection.StartAsync();
                Console.WriteLine("Connected to OmniSync Hub.");

                // Authenticate
                var isAuthenticated = await connection.InvokeAsync<bool>("Authenticate", _apiKey);

                if (isAuthenticated)
                {
                    Console.WriteLine("Authenticated successfully.");
                    Console.WriteLine($"Executing command: {commandToExecute}");
                    await connection.InvokeAsync("ExecuteCommand", commandToExecute);
                    
                    // Wait for a short period to allow output to be received, then exit.
                    await Task.Delay(TimeSpan.FromSeconds(5)); // Wait 5 seconds
                }
                else
                {
                    Console.WriteLine("Authentication failed. Please check your API key.");
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error connecting or communicating with the hub: {ex.Message}");
            }
            finally
            {
                if (connection.State == HubConnectionState.Connected)
                {
                    await connection.StopAsync();
                    Console.WriteLine("Disconnected from OmniSync Hub.");
                }
            }
        }
    }
}
