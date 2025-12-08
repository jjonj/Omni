using System;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Hosting;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services
{
    public class ClipboardHostedService : IHostedService
    {
        private readonly ClipboardService _clipboardService;
        private readonly IHubContext<RpcApiHub> _hubContext;

        public ClipboardHostedService(ClipboardService clipboardService, IHubContext<RpcApiHub> hubContext)
        {
            _clipboardService = clipboardService;
            _hubContext = hubContext;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _clipboardService.ClipboardTextChanged += OnClipboardTextChanged;
            _clipboardService.Start();
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _clipboardService.Stop();
            _clipboardService.ClipboardTextChanged -= OnClipboardTextChanged;
            return Task.CompletedTask;
        }

        private void OnClipboardTextChanged(object sender, string newText)
        {
            _hubContext.Clients.All.SendAsync("ClipboardUpdated", newText);
        }
    }
}
