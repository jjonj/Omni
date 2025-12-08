using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Infrastructure.Services;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Services
{
    public class HubEventSender
    {
        private readonly IHubContext<RpcApiHub> _hubContext;
        private readonly ProcessService _processService;
        private readonly Dictionary<string, string> _clientCommandOutputSubscriptions = new Dictionary<string, string>(); // ClientId -> ConnectionId for command output

        public HubEventSender(IHubContext<RpcApiHub> hubContext, ProcessService processService)
        {
            _hubContext = hubContext;
            _processService = processService;
            _processService.CommandOutputReceived += OnCommandOutputReceived;
        }

        // Method to be called by RpcApiHub when a client connects and wants command output
        public void SubscribeForCommandOutput(string clientId, string connectionId)
        {
            _clientCommandOutputSubscriptions[clientId] = connectionId;
        }

        public void UnsubscribeFromCommandOutput(string clientId)
        {
            _clientCommandOutputSubscriptions.Remove(clientId);
        }

        private async void OnCommandOutputReceived(object sender, string output)
        {
            // Iterate over all subscribed clients and send the output
            foreach (var connectionId in _clientCommandOutputSubscriptions.Values.ToList()) // Use ToList() to avoid modification during iteration
            {
                try
                {
                    await _hubContext.Clients.Client(connectionId).SendAsync("ReceiveCommandOutput", output);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error sending command output to client {connectionId}: {ex.Message}");
                    // Optionally, remove disconnected clients from the subscription list here.
                }
            }
        }
    }
}
