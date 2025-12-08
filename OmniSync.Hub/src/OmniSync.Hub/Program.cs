using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration; // Added for configuration access
using OmniSync.Hub.Logic.Services; // Added for AuthService
using OmniSync.Hub.Presentation.Hubs;
// ... (other code) ...
                endpoints.MapHub<RpcApiHub>("/signalrhub");
    .Build()
    .Run();
