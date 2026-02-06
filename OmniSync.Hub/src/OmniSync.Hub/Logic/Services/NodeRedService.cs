using System;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services
{
    public class NodeRedService
    {
        private readonly ILogger<NodeRedService> _logger;
        private readonly HttpClient _httpClient;
        private readonly string _nodeRedBaseUrl = "http://localhost:1880";

        public NodeRedService(ILogger<NodeRedService> logger)
        {
            _logger = logger;
            _httpClient = new HttpClient { BaseAddress = new Uri(_nodeRedBaseUrl) };
        }

        public async Task<bool> TriggerFlowAsync(string endpoint, object? payload = null)
        {
            try
            {
                _logger.LogInformation($"Triggering Node-RED flow: {endpoint}");
                
                string json = payload != null ? JsonSerializer.Serialize(payload) : "{}";
                var content = new StringContent(json, Encoding.UTF8, "application/json");
                
                var response = await _httpClient.PostAsync(endpoint, content);
                
                if (response.IsSuccessStatusCode)
                {
                    _logger.LogInformation($"Successfully triggered Node-RED flow: {endpoint}");
                    return true;
                }
                else
                {
                    _logger.LogWarning($"Failed to trigger Node-RED flow: {endpoint}. Status: {response.StatusCode}");
                    return false;
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error triggering Node-RED flow: {endpoint}");
                return false;
            }
        }
    }
}
