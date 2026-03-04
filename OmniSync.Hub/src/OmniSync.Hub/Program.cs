using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Presentation;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Logic.Monitoring; // For the new monitoring service
using System;
using System.IO; // Added for Path.Combine and Directory.GetCurrentDirectory()

using Microsoft.Extensions.FileProviders; // Added for PhysicalFileProvider
using Microsoft.AspNetCore.Hosting; // Added for ConfigureKestrel
using Microsoft.AspNetCore.SignalR; // Added for IHubContext
using Microsoft.AspNetCore.SignalR.Client; // Added for HubConnectionBuilder extension methods
using System.Threading.Tasks; // For TaskScheduler events
using System.Text; // For StringBuilder
using System.Threading; // Added for Mutex

// Set the current directory to the location of the executable to ensure
// consistent behavior for file paths (config, static files) regardless of startup method.
Directory.SetCurrentDirectory(AppContext.BaseDirectory);

// --- Single Instance Check ---
using var mutex = new Mutex(true, "Global\\OmniSyncHubSingleInstance", out bool createdNew);
if (!createdNew)
{
    var config = new ConfigurationBuilder()
        .AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.json"), optional: true)
        .Build();
    
    string? apiKey = config["AuthApiKey"];
    if (string.IsNullOrEmpty(apiKey))
    {
        string altPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "appsettings.json");
        if (File.Exists(altPath))
        {
            config = new ConfigurationBuilder().AddJsonFile(altPath).Build();
            apiKey = config["AuthApiKey"];
        }
    }

    if (args.Length == 0)
    {
        Console.WriteLine("OmniSync Hub is already running. Signaling existing instance to show window...");
        await ForwardToHub(apiKey, "SHOW_WINDOW", "");
    }
    else if (args[0] == "--open-on-android" && args.Length > 1)
    {
        await ForwardToHub(apiKey, "OpenOnAndroid", args[1]);
    }
    else if (args[0] == "--cli-here" && args.Length > 1)
    {
        await ForwardToHub(apiKey, "CliHere", args[1]);
    }
    else
    {
        Console.WriteLine("OmniSync Hub is already running. Exiting.");
    }
    return;
}

// --- Command Line Argument Handling ---
if (args.Length > 0)
{
    var config = new ConfigurationBuilder()
        .AddJsonFile(Path.Combine(AppContext.BaseDirectory, "appsettings.json"), optional: true)
        .Build();
    
    string? apiKey = config["AuthApiKey"];
    if (string.IsNullOrEmpty(apiKey))
    {
        // Try absolute path if relative fails
        string altPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "appsettings.json");
        if (File.Exists(altPath))
        {
            config = new ConfigurationBuilder().AddJsonFile(altPath).Build();
            apiKey = config["AuthApiKey"];
        }
    }

    if (args[0] == "--open-on-android" && args.Length > 1)
    {
        string filePath = args[1];
        Console.WriteLine($"Sending command to Hub: Open on Android -> {filePath}");
        await ForwardToHub(apiKey, "OpenOnAndroid", filePath);
        return;
    }
    else if (args[0] == "--cli-here" && args.Length > 1)
    {
        string workspace = args[1];
        Console.WriteLine($"Sending command to Hub: CLI Here -> {workspace}");
        await ForwardToHub(apiKey, "CliHere", workspace);
        return;
    }
}

static async Task ForwardToHub(string? apiKey, string command, string payload)
{
    if (string.IsNullOrEmpty(apiKey))
    {
        Console.WriteLine("Error: AuthApiKey not found. Cannot forward command to Hub.");
        return;
    }

    try
    {
        var hubConnection = new Microsoft.AspNetCore.SignalR.Client.HubConnectionBuilder()
            .WithUrl("http://localhost:5000/signalrhub")
            .Build();

        await hubConnection.StartAsync();
        bool authenticated = await hubConnection.InvokeAsync<bool>("Authenticate", apiKey);
        
        if (authenticated)
        {
            if (command == "OpenOnAndroid")
            {
                await hubConnection.InvokeAsync("HandleExternalCommand", "OPEN_FILE_ON_ANDROID", payload);
            }
            else if (command == "CliHere")
            {
                await hubConnection.InvokeAsync("HandleExternalCommand", "CLI_HERE", payload);
            }
            else if (command == "SHOW_WINDOW")
            {
                await hubConnection.InvokeAsync("HandleExternalCommand", "SHOW_WINDOW", payload);
            }
        }
        else
        {
            Console.WriteLine("Error: Authentication failed when forwarding to Hub.");
        }
        await hubConnection.StopAsync();
    }
    catch (Exception ex)
    {
        Console.WriteLine($"Error forwarding to Hub: {ex.Message}");
    }
}

// --- Global Exception Handling for Crashes ---
CrashHandler.Initialize();

var builder = WebApplication.CreateBuilder(args);

// Configure logging
builder.Logging.ClearProviders();
builder.Logging.AddConsole();
string logFilePath = Path.Combine(AppContext.BaseDirectory, "hub_log.txt");
// Try to log to solution root if running from bin
string rootLogPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "hub_log.txt");
if (Directory.Exists(Path.GetDirectoryName(rootLogPath))) logFilePath = rootLogPath;

// Clear the log file on startup if it exists
try
{
    if (File.Exists(logFilePath))
    {
        File.WriteAllText(logFilePath, string.Empty);
    }

    // Also clear gemini_cli_debug.log
    string debugLogPath = Path.Combine(Path.GetDirectoryName(logFilePath) ?? "", "gemini_cli_debug.log");
    if (File.Exists(debugLogPath))
    {
        File.WriteAllText(debugLogPath, string.Empty);
    }
}
catch (Exception ex)
{
    Console.WriteLine($"Warning: Could not clear log file at startup: {ex.Message}");
}

builder.Logging.AddProvider(new FileLoggerProvider(logFilePath));


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
builder.Services.AddHttpClient();
builder.Services.AddSingleton<CalendarService>();
builder.Services.AddSingleton<ProjectSearchService>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<CalendarService>());
builder.Services.AddSingleton<GitService>();
builder.Services.AddSingleton<IMacroService, MacroService>();
builder.Services.AddSingleton<ClipboardService>();
builder.Services.AddSingleton<ProcessService>(provider =>
{
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    return new ProcessService(settingsService, monitorService);
});
builder.Services.AddSingleton<ProjectLauncherService>();
builder.Services.AddSingleton<ResourceOpenerService>(provider =>
{
    var processService = provider.GetRequiredService<ProcessService>();
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    return new ResourceOpenerService(processService, settingsService, monitorService);
});
builder.Services.AddSingleton<AiCliService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<AiCliService>>();
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    var processService = provider.GetRequiredService<ProcessService>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    var configuration = provider.GetRequiredService<IConfiguration>();
    return new AiCliService(logger, settingsService, processService, monitorService, configuration);
});
builder.Services.AddSingleton<InputService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<InputService>>();
    var keyboardHook = provider.GetRequiredService<KeyboardHook>();
    return new InputService(logger, keyboardHook);
});

builder.Services.AddSingleton<AudioService>();
builder.Services.AddSingleton<HubSettingsService>();
builder.Services.AddSingleton<QuickActionService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<QuickActionService>>();
    var configuration = provider.GetRequiredService<IConfiguration>();
    var hubContext = provider.GetRequiredService<IHubContext<RpcApiHub>>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    var aiCliService = provider.GetRequiredService<AiCliService>();
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    return new QuickActionService(logger, configuration, provider, hubContext, monitorService, aiCliService, settingsService);
});
builder.Services.AddSingleton<CommandDispatcher>(provider => {
    var inputService = provider.GetRequiredService<InputService>();
    var fileService = provider.GetRequiredService<FileService>();
    var audioService = provider.GetRequiredService<AudioService>();
    var processService = provider.GetRequiredService<ProcessService>();
    var screenshotService = provider.GetRequiredService<ScreenshotService>();
    var shutdownService = provider.GetRequiredService<ShutdownService>();
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    var pcgService = provider.GetRequiredService<PcgPersistentService>();
    var nodeRedService = provider.GetRequiredService<NodeRedService>();
    var projectLauncherService = provider.GetRequiredService<ProjectLauncherService>();
    var resourceOpenerService = provider.GetRequiredService<ResourceOpenerService>();
    var aiCliService = provider.GetRequiredService<AiCliService>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    var appLifetime = provider.GetRequiredService<IHostApplicationLifetime>();
    var hubContext = provider.GetRequiredService<IHubContext<RpcApiHub>>();
    return new CommandDispatcher(inputService, fileService, audioService, processService, screenshotService, shutdownService, settingsService, pcgService, nodeRedService, projectLauncherService, resourceOpenerService, aiCliService, monitorService, appLifetime, hubContext);
});
builder.Services.AddSingleton<GlobalHotkeyService>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<GlobalHotkeyService>());

builder.Services.AddSingleton<ShutdownService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<ShutdownService>>();
    var processService = provider.GetRequiredService<ProcessService>();
    var audioService = provider.GetRequiredService<AudioService>();
    var fileService = provider.GetRequiredService<FileService>();
    return new ShutdownService(logger, processService, audioService, fileService);
});
builder.Services.AddSingleton<RegistryService>();
builder.Services.AddSingleton<ScreenshotService>();
builder.Services.AddSingleton<LayoutCaptureService>();
builder.Services.AddSingleton<AssistantService>();
builder.Services.AddSingleton<PcgPersistentService>();
builder.Services.AddSingleton<NodeRedService>();
builder.Services.AddSingleton<HubMonitorService>();
builder.Services.AddSingleton<BookProgressService>();
builder.Services.AddHostedService<HubMonitorService>(provider => provider.GetRequiredService<HubMonitorService>());
builder.Services.AddSingleton<HubEventSender>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<HubEventSender>>();
    var hubContext = provider.GetRequiredService<IHubContext<RpcApiHub>>();
    var processService = provider.GetRequiredService<ProcessService>();
    var inputService = provider.GetRequiredService<InputService>();
    var audioService = provider.GetRequiredService<AudioService>();
    var shutdownService = provider.GetRequiredService<ShutdownService>();
    var commandDispatcher = provider.GetRequiredService<CommandDispatcher>();
    var fileService = provider.GetRequiredService<FileService>(); // Get FileService
    var aiCliService = provider.GetRequiredService<AiCliService>(); // Get AiCliService
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    var assistantService = provider.GetRequiredService<AssistantService>();

    return new HubEventSender(logger, hubContext, processService, inputService, audioService, shutdownService, commandDispatcher, fileService, aiCliService, settingsService, monitorService, assistantService);
});
builder.Services.AddSingleton<TrayIconManager>(provider =>
{
    var appLifetime = provider.GetRequiredService<IHostApplicationLifetime>();
    var hubMonitorService = provider.GetRequiredService<HubMonitorService>();
    var inputService = provider.GetRequiredService<InputService>();
    var processService = provider.GetRequiredService<ProcessService>();
    var shutdownService = provider.GetRequiredService<ShutdownService>();
    var registryService = provider.GetRequiredService<RegistryService>();
    var settingsService = provider.GetRequiredService<HubSettingsService>();
    var hotkeyService = provider.GetRequiredService<GlobalHotkeyService>();
    var keyboardHook = provider.GetRequiredService<KeyboardHook>();
    var aiCliService = provider.GetRequiredService<AiCliService>();
    var layoutCaptureService = provider.GetRequiredService<LayoutCaptureService>();
    var projectLauncherService = provider.GetRequiredService<ProjectLauncherService>();
    var commandDispatcher = provider.GetRequiredService<CommandDispatcher>();
    var calendarService = provider.GetRequiredService<CalendarService>();
    var searchService = provider.GetRequiredService<ProjectSearchService>();
    var macroService = provider.GetRequiredService<IMacroService>();
    var resourceOpenerService = provider.GetRequiredService<ResourceOpenerService>();
    var hubEventSender = provider.GetRequiredService<HubEventSender>();
    var assistantService = provider.GetRequiredService<AssistantService>();
    var logger = provider.GetRequiredService<ILogger<TrayIconManager>>();
    return new TrayIconManager(appLifetime, hubMonitorService, inputService, processService, shutdownService, registryService, settingsService, hotkeyService, keyboardHook, aiCliService, layoutCaptureService, projectLauncherService, commandDispatcher, calendarService, searchService, macroService, resourceOpenerService, hubEventSender, logger, assistantService);
});
builder.Services.AddHostedService<TrayIconManager>(provider => provider.GetRequiredService<TrayIconManager>());

builder.Services.AddHostedService<HubStartupService>(); // Auto-launch AI components
builder.Services.AddHostedService<ScreenshotHostedService>();
builder.Services.AddHostedService<WebReloaderService>();
builder.Services.AddHostedService<UpnpService>();
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

builder.Services.AddSignalR(options => {
    options.MaximumReceiveMessageSize = 10 * 1024 * 1024; // 10MB
});
builder.Services.AddControllers();

var app = builder.Build();

// Register Context Menu at startup
using (var scope = app.Services.CreateScope())
{
    var registryService = scope.ServiceProvider.GetRequiredService<RegistryService>();
    registryService.RegisterContextMenu();
}

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseDeveloperExceptionPage();
}

// Disable caching for development to prevent stale web assets
app.Use(async (context, next) =>
{
    context.Response.Headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0";
    context.Response.Headers["Pragma"] = "no-cache";
    context.Response.Headers["Expires"] = "0";
    await next();
});

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

// --- Global Exception Handling for Crashes ---
CrashHandler.Initialize();

try
{

    app.Run();
}
catch (Exception ex)
{
    CrashHandler.HandleCrash("MainLoopException", ex);
    throw;
}
