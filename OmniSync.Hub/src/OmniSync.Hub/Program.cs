using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Presentation;
using OmniSync.Hub.Logic.Monitoring; // For the new monitoring service
using System;
using System.IO; // Added for Path.Combine and Directory.GetCurrentDirectory()

using Microsoft.Extensions.FileProviders; // Added for PhysicalFileProvider
using Microsoft.AspNetCore.Hosting; // Added for ConfigureKestrel
using Microsoft.AspNetCore.SignalR; // Added for IHubContext

// Set the current directory to the location of the executable to ensure
// consistent behavior for file paths (config, static files) regardless of startup method.
Directory.SetCurrentDirectory(AppContext.BaseDirectory);

var builder = WebApplication.CreateBuilder(args);

// Configure Kestrel to listen on multiple ports
builder.WebHost.ConfigureKestrel(options =>
{
    options.ListenAnyIP(5000); // SignalR and APIs
    options.ListenAnyIP(3333); // Webserver
});

// Explicitly load appsettings.json using absolute path - Make optional so it doesn't crash in production
// Attempt to load from solution root if running locally (5 levels up from bin/Debug/net9.0-windows)
string appSettingsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "appsettings.json");
builder.Configuration.AddJsonFile(appSettingsPath, optional: true, reloadOnChange: true);


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
builder.Services.AddSingleton<CommandDispatcher>(provider => {
    var inputService = provider.GetRequiredService<InputService>();
    var fileService = provider.GetRequiredService<FileService>();
    var audioService = provider.GetRequiredService<AudioService>();
    var processService = provider.GetRequiredService<ProcessService>();
    var shutdownService = provider.GetRequiredService<ShutdownService>();
    var appLifetime = provider.GetRequiredService<IHostApplicationLifetime>();
    return new CommandDispatcher(inputService, fileService, audioService, processService, shutdownService, appLifetime);
});
builder.Services.AddSingleton<ProcessService>();
builder.Services.AddSingleton<InputService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<InputService>>();
    var keyboardHook = provider.GetRequiredService<KeyboardHook>();
    return new InputService(logger, keyboardHook);
});
builder.Services.AddSingleton<AudioService>();
builder.Services.AddSingleton<ShutdownService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<ShutdownService>>();
    var processService = provider.GetRequiredService<ProcessService>();
    var audioService = provider.GetRequiredService<AudioService>();
    var fileService = provider.GetRequiredService<FileService>();
    return new ShutdownService(logger, processService, audioService, fileService);
});
builder.Services.AddSingleton<RegistryService>();
builder.Services.AddSingleton<HubEventSender>(provider =>
{
    var hubContext = provider.GetRequiredService<IHubContext<RpcApiHub>>();
    var processService = provider.GetRequiredService<ProcessService>();
    var inputService = provider.GetRequiredService<InputService>();
    var shutdownService = provider.GetRequiredService<ShutdownService>();
    var commandDispatcher = provider.GetRequiredService<CommandDispatcher>();
    var fileService = provider.GetRequiredService<FileService>(); // Get FileService

    return new HubEventSender(hubContext, processService, inputService, shutdownService, commandDispatcher, fileService);
});
builder.Services.AddSingleton<HubMonitorService>(); // Register the new monitoring service
builder.Services.AddHostedService<TrayIconManager>();
builder.Services.AddHostedService<HubStartupService>(); // Auto-launch AI components
builder.Services.AddSingleton<KeyboardHook>(); // Register KeyboardHook

builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll",
        builder =>
        {
            builder.SetIsOriginAllowed(_ => true)
                   .AllowAnyMethod()
                   .AllowAnyHeader()
                   .AllowCredentials();
        });
});

builder.Services.AddSignalR();
builder.Services.AddControllers();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}

app.UseCors("AllowAll");

app.UseRouting();

// Serve static files from OmniSync.Web\www
// Try to find the folder relative to CWD (Dev) or relative to BaseDirectory (Prod)
string[] possibleWebPaths = new[]
{
    Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "OmniSync.Web", "www") // 6 levels up from bin/Debug/net9.0-windows to Root -> OmniSync.Webroot
};

string? webContentPath = possibleWebPaths.FirstOrDefault(Directory.Exists);

if (webContentPath != null)
{
    app.UseDefaultFiles(new DefaultFilesOptions
    {
        FileProvider = new PhysicalFileProvider(webContentPath),
        DefaultFileNames = new List<string> { "index.html", "Scheduler.html", "Test.html" } 
    });
    app.UseStaticFiles(new StaticFileOptions
    {
        FileProvider = new PhysicalFileProvider(webContentPath),
        RequestPath = ""
    });
}

// Assuming no explicit authentication middleware is needed beyond SignalR's internal one for simplicity.
// If there's an actual authentication scheme, it would go here: app.UseAuthentication();
app.UseAuthorization(); // Even if empty, it's good practice if Authorization is ever considered.

app.MapControllers();
app.MapHub<RpcApiHub>("/signalrhub");

app.Run();