using Microsoft.Extensions.DependencyInjection;
using OmniSync.Hub.Logic.Services.VoiceInbox;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public static class AssistantCommandRegistration
{
    public static IServiceCollection AddAssistantCommandFoundation(
        this IServiceCollection services)
    {
        services.AddSingleton(TimeProvider.System);

        services.AddSingleton<ReflectionCommandProvider>();
        services.AddSingleton<IAssistantCommandProvider>(provider =>
            provider.GetRequiredService<ReflectionCommandProvider>());

        services.AddSingleton<MacroCommandProvider>();
        services.AddSingleton<IAssistantCommandProvider>(provider =>
            provider.GetRequiredService<MacroCommandProvider>());

        services.AddSingleton<AssistantDeviceRegistry>();
        services.AddSingleton<DeviceCommandProvider>();
        services.AddSingleton<IAssistantCommandProvider>(provider =>
            provider.GetRequiredService<DeviceCommandProvider>());
        services.AddSingleton<AssistantHostSelector>();

        services.AddSingleton<AssistantCommandCatalog>();
        services.AddSingleton<AssistantRunStore>();
        services.AddSingleton<AssistantRunEventSink>();
        services.AddSingleton<AssistantRemoteDispatcher>();
        services.AddSingleton<AssistantCommandDispatcherService>();
        services.AddHostedService(provider =>
            provider.GetRequiredService<AssistantCommandDispatcherService>());
        services.AddSingleton<AssistantRequestService>();

        services.AddSingleton<DeterministicCommandVoiceHandler>();
        services.AddSingleton<IVoiceTextHandler>(provider =>
            provider.GetRequiredService<DeterministicCommandVoiceHandler>());

        return services;
    }
}
