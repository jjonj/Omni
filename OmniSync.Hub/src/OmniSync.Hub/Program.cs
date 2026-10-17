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
using OmniSync.Hub.Logic.Services.AssistantCommands;
using OmniSync.Hub.Logic.Services.Mcp;
using System;
using System.Net;
using System.Net.Sockets;
using System.IO; // Added for Path.Combine and Directory.GetCurrentDirectory()

using Microsoft.Extensions.FileProviders; // Added for PhysicalFileProvider
using Microsoft.AspNetCore.Hosting; // Added for ConfigureKestrel
using Microsoft.AspNetCore.Http;
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

// Keep the previous run's last messages: they are vital when investigating a crash.
// Archive oversized logs without deleting their history; never truncate on startup.
try
{
    if (File.Exists(logFilePath) && new FileInfo(logFilePath).Length > 50 * 1024 * 1024)
    {
        Directory.CreateDirectory(CrashHandler.LogDirectory);
        var archivedPath = Path.Combine(CrashHandler.LogDirectory,
            $"hub_log_{DateTime.UtcNow:yyyyMMdd_HHmmss_fff}_{Guid.NewGuid():N}.txt");
        File.Move(logFilePath, archivedPath);
    }

    File.AppendAllText(logFilePath,
        $"{Environment.NewLine}===== OmniHub START {DateTimeOffset.Now:O} PID {Environment.ProcessId} ====={Environment.NewLine}");
}
catch (Exception ex)
{
    Console.WriteLine($"Warning: Could not prepare persistent log file: {ex.Message}");
}

builder.Logging.AddProvider(new FileLoggerProvider(logFilePath));


// MCP server (full PC access). Resolved before Kestrel is configured so a busy port disables MCP instead of crashing the Hub.
var mcpOptions = ResolveMcpOptions(builder.Configuration);

// Configure Kestrel to listen on multiple ports
builder.WebHost.ConfigureKestrel(options =>
{
    options.ListenAnyIP(5000); // SignalR and APIs
    options.ListenAnyIP(3333); // Webserver (Gateway routes delegate to Rust 3334 when opted in)
    options.ListenAnyIP(GoogleHomePort); // Google Home bridge, the only port exposed through the public HTTPS tunnel
    if (mcpOptions.Enabled) options.Listen(mcpOptions.Address, mcpOptions.Port); // MCP is served only on this port
});

// Explicitly load appsettings.json using absolute path - Make optional so it doesn't crash in production
// Attempt to load from solution root if running locally (5 levels up from bin/Debug/net9.0-windows)
string appSettingsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "appsettings.json");
builder.Configuration.AddJsonFile(appSettingsPath, optional: true, reloadOnChange: true);


// Add services to the container.
builder.Services.AddSingleton<AuthService>(new AuthService(builder.Configuration["AuthApiKey"] ?? throw new InvalidOperationException("AuthApiKey not configured.")));
builder.Services.AddSingleton<AndroidUpdateDeploymentCoordinator>();
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
builder.Services.AddSingleton<UgoiraService>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<UgoiraService>());
builder.Services.AddSingleton<CalendarService>();
builder.Services.AddSingleton<IReminderRepository, ReminderRepository>();
builder.Services.AddSingleton<CalendarReminderSyncService>();
builder.Services.AddSingleton<ProjectSearchService>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<CalendarService>());
builder.Services.AddHostedService(sp => sp.GetRequiredService<CalendarReminderSyncService>());
builder.Services.AddHostedService<ReminderLifecycleService>();
builder.Services.AddHostedService<ReminderAttentionService>();
builder.Services.AddSingleton<GitService>();
builder.Services.AddSingleton<IVoiceSceneService, VoiceSceneService>();
builder.Services.AddSingleton<IMacroService, MacroService>();
builder.Services.AddSingleton<GoogleHomeService>(provider => ActivatorUtilities.CreateInstance<GoogleHomeService>(
    provider, provider.GetRequiredService<System.Net.Http.IHttpClientFactory>().CreateClient("GoogleHome")));
// Voice inbox: deterministic catalogue first, then future resolver/skill fallbacks.
builder.Services.AddAssistantCommandFoundation();
builder.Services.AddSingleton<OmniSync.Hub.Logic.Services.VoiceInbox.VoiceInboxService>();
builder.Services.AddHostedService<OmniSync.Hub.Logic.Services.VoiceInbox.KeepBridgeHostedService>();
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
builder.Services.AddSingleton<AgentSessionService>();
builder.Services.AddSingleton<QuickActionService>(provider =>
{
    var logger = provider.GetRequiredService<ILogger<QuickActionService>>();
    var configuration = provider.GetRequiredService<IConfiguration>();
    var hubContext = provider.GetRequiredService<IHubContext<RpcApiHub>>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    return new QuickActionService(logger, configuration, provider, hubContext, monitorService);
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
    var clipboardService = provider.GetRequiredService<ClipboardService>();
    var aiCliService = provider.GetRequiredService<AiCliService>();
    var monitorService = provider.GetRequiredService<HubMonitorService>();
    var appLifetime = provider.GetRequiredService<IHostApplicationLifetime>();
    var hubContext = provider.GetRequiredService<IHubContext<RpcApiHub>>();
    return new CommandDispatcher(inputService, fileService, audioService, processService, screenshotService, shutdownService, settingsService, pcgService, nodeRedService, projectLauncherService, resourceOpenerService, clipboardService, aiCliService, monitorService, appLifetime, hubContext);
});
builder.Services.AddSingleton<ITerminalService, TerminalService>();
builder.Services.AddHostedService<TerminalOutputBroadcaster>();
builder.Services.AddSingleton<RemoteTerminalSessionManager>();
builder.Services.AddSingleton<RemoteTerminalBridgeEndpoint>();
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
builder.Services.AddSingleton<PcgPersistentService>();
builder.Services.AddSingleton<NodeRedService>();
builder.Services.AddSingleton<HubMonitorService>();
builder.Services.AddSingleton<BookProgressService>();
builder.Services.AddHostedService<HubMonitorService>(provider => provider.GetRequiredService<HubMonitorService>());
builder.Services.AddHostedService<MemoryWatchdogService>();
builder.Services.AddHostedService<OmniMcpLiteWatchdogService>();
builder.Services.AddHostedService<ConnectivityAlertService>();
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

    return new HubEventSender(logger, hubContext, processService, inputService, audioService, shutdownService, commandDispatcher, fileService, aiCliService, settingsService, monitorService);
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
    var clipboardService = provider.GetRequiredService<ClipboardService>();
    var logger = provider.GetRequiredService<ILogger<TrayIconManager>>();
    return new TrayIconManager(appLifetime, hubMonitorService, inputService, processService, shutdownService, registryService, settingsService, hotkeyService, keyboardHook, aiCliService, layoutCaptureService, projectLauncherService, commandDispatcher, calendarService, searchService, macroService, resourceOpenerService, hubEventSender, logger, clipboardService);
});
builder.Services.AddHostedService<TrayIconManager>(provider =>
provider.GetRequiredService<TrayIconManager>());

builder.Services.AddHostedService<OmniZonesHostedService>(); // OmniZones window snapping (OmniZones.Core)
builder.Services.AddHostedService<HubStartupService>(); // Auto-launch AI components
builder.Services.AddHostedService<ScreenshotHostedService>();
builder.Services.AddHostedService<UpnpService>();
builder.Services.AddSingleton<ExternalIpService>();
builder.Services.AddHostedService<ExternalIpService>(provider => provider.GetRequiredService<ExternalIpService>());
builder.Services.AddSingleton<KeyboardHook>(); // Register KeyboardHook

// MCP server: shell, background processes and file tools for the whole PC
builder.Services.AddSingleton(mcpOptions);
builder.Services.AddSingleton<OmniSync.Hub.Logic.Services.Gateway.GatewayToolManager>();
builder.Services.AddSingleton<OmniSync.Hub.Logic.Services.Gateway.GatewaySweepService>();
builder.Services.AddSingleton<ShellProcessRegistry>();
builder.Services.AddSingleton<AgentCursorService>();
builder.Services.AddSingleton<McpTools>();
builder.Services.AddSingleton<McpEndpoint>();

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

app.Services.GetRequiredService<HubMonitorService>().AddLogMessage($"[MCP] {mcpOptions.Summary}");

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

// The MCP port serves only the MCP endpoint.
app.Use(async (context, next) =>
{
    if (mcpOptions.Enabled && context.Connection.LocalPort == mcpOptions.Port)
    {
        await context.RequestServices.GetRequiredService<McpEndpoint>().HandleAsync(context);
        return;
    }
    await next();
});

// The Google Home port is reachable from the internet through the tunnel: serve nothing but /google-home on it.
app.Use(async (context, next) =>
{
    if (context.Connection.LocalPort == GoogleHomePort)
    {
        var monitor = context.RequestServices.GetRequiredService<HubMonitorService>();
        monitor.AddLogMessage($"[GoogleHome] {context.Request.Method} {context.Request.Path}{context.Request.QueryString}");
        try
        {
            File.AppendAllText(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync", "google_home.log"),
                $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {context.Request.Method} {context.Request.Path}{context.Request.QueryString}{Environment.NewLine}");
        }
        catch { }
        if (!context.Request.Path.StartsWithSegments("/google-home"))
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            await context.Response.WriteAsync($"OmniHub: unknown path {context.Request.Path}. Google Home endpoints live under /google-home/ (auth, token, fulfillment).");
            return;
        }
    }
    await next();
});

app.UseWebSockets();
// All pre-existing Android devices retain http://<hub>:5000/signalrhub.
// During explicit Rust delegation, the C# listener relays external-device
// traffic on those routes to Rust :5001 without stealing C# localhost clients.
app.Use(async (context, next) =>
{
    if (AndroidRustPortBridge.ShouldHandle(context))
    {
        await AndroidRustPortBridge.ForwardAsync(context, app.Configuration);
        return;
    }
    await next();
});
app.UseRouting();
OmniSync.Hub.Logic.Services.Gateway.GatewayRoutes.UseRootRedirect(app);

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
OmniSync.Hub.Logic.Services.Gateway.GatewayRoutes.Map(app);
OmniSync.Hub.Logic.Services.Gateway.GatewaySweepRoutes.Map(app);
app.MapHub<RpcApiHub>("/signalrhub");
app.MapHub<TerminalHub>("/terminalhub");
app.MapHub<RemoteTerminalHub>("/remoteterminalhub");
app.Map("/internal/remote-terminal/{sessionId}", async context =>
{
    var sessionId = context.Request.RouteValues["sessionId"]?.ToString();
    if (string.IsNullOrEmpty(sessionId))
    {
        context.Response.StatusCode = StatusCodes.Status404NotFound;
        return;
    }
    await context.RequestServices.GetRequiredService<RemoteTerminalBridgeEndpoint>().HandleAsync(context, sessionId);
});

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

partial class Program
{
    internal const int GoogleHomePort = 5055;

    private static readonly int[] HubReservedPorts = { 5000, 3333, GoogleHomePort };

    /// Resolves the "Mcp" config section. Any problem (bad host, reserved or busy port) disables MCP instead of stopping the Hub.
    internal static McpOptions ResolveMcpOptions(IConfiguration config)
    {
        var host = config["Mcp:Host"] ?? "127.0.0.1";
        var port = config.GetValue("Mcp:Port", 8765);
        if (!config.GetValue("Mcp:Enabled", true))
            return new McpOptions(false, IPAddress.Loopback, port, "MCP disabled in appsettings");

        IPAddress? address = host.Equals("localhost", StringComparison.OrdinalIgnoreCase)
            ? IPAddress.Loopback
            : IPAddress.TryParse(host, out var parsed) ? parsed : null;
        if (address == null)
            return new McpOptions(false, IPAddress.Loopback, port, $"MCP disabled: invalid Mcp:Host '{host}'");
        if (Array.IndexOf(HubReservedPorts, port) >= 0)
            return new McpOptions(false, address, port, $"MCP disabled: port {port} is used by the Hub");

        try
        {
            var probe = new TcpListener(address, port);
            probe.Start();
            probe.Stop();
        }
        catch (SocketException ex)
        {
            return new McpOptions(false, address, port, $"MCP disabled: port {port} unavailable ({ex.Message})");
        }

        return new McpOptions(true, address, port, $"MCP listening on http://{host}:{port}/mcp");
    }
}
