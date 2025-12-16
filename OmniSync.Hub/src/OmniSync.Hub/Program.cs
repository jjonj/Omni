using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Presentation;
using OmniSync.Hub.Logic.Monitoring; // For the new monitoring service
using System;
using System.IO; // Added for Path.Combine and Directory.GetCurrentDirectory()

var builder = WebApplication.CreateBuilder(args);

// Set URLs through configuration
builder.Configuration["Urls"] = "http://0.0.0.0:5000";

// Explicitly load appsettings.json using absolute path
string appSettingsPath = Path.Combine(Directory.GetCurrentDirectory(), "..", "..", "appsettings.json");
builder.Configuration.AddJsonFile(appSettingsPath, optional: false, reloadOnChange: true);


// Add services to the container.
builder.Services.AddSingleton<AuthService>(new AuthService(builder.Configuration["AuthApiKey"] ?? throw new InvalidOperationException("AuthApiKey not configured.")));
builder.Services.AddSingleton<FileService>(provider =>
{
    var configuration = provider.GetRequiredService<IConfiguration>();
    var noteRootPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "Obsidian"); // Default to Obsidian
    var browseRootPath = configuration["FileService:BrowseRootPath"] ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile); // Configurable, or default to user profile

    // Ensure the note root path exists
    if (!Directory.Exists(noteRootPath))
    {
        Directory.CreateDirectory(noteRootPath);
    }
    // No need to ensure browseRootPath exists here, as it's for browsing
    
    return new FileService(noteRootPath, browseRootPath);
});
builder.Services.AddSingleton<ClipboardService>();
builder.Services.AddSingleton<CommandDispatcher>();
builder.Services.AddSingleton<ProcessService>();
builder.Services.AddSingleton<InputService>();
builder.Services.AddSingleton<AudioService>();
builder.Services.AddSingleton<HubEventSender>();
builder.Services.AddSingleton<HubMonitorService>(); // Register the new monitoring service
builder.Services.AddHostedService<TrayIconManager>();

builder.Services.AddSignalR();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}

app.UseRouting();

// Assuming no explicit authentication middleware is needed beyond SignalR's internal one for simplicity.
// If there's an actual authentication scheme, it would go here: app.UseAuthentication();
app.UseAuthorization(); // Even if empty, it's good practice if Authorization is ever considered.

app.MapHub<RpcApiHub>("/signalrhub");

app.Run();