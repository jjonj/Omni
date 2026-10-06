using Microsoft.Extensions.DependencyInjection;
using OmniSync.Hub.Logic.Services.VoiceInbox;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public static class AssistantCommandRegistration
{
    public static IServiceCollection AddAssistantCommandFoundation(
        this IServiceCollection services)
    {
        var commandTypes = typeof(AssistantCommandRegistration).Assembly
            .GetTypes()
            .Where(type =>
                !type.IsAbstract &&
                !type.IsInterface &&
                typeof(IAssistantCommand).IsAssignableFrom(type))
            .ToList();

        foreach (var commandType in commandTypes)
            services.AddSingleton(typeof(IAssistantCommand), commandType);

        services.AddSingleton<AssistantCommandRegistry>();
        services.AddSingleton<AssistantCommandScheduler>();
        services.AddHostedService(provider =>
            provider.GetRequiredService<AssistantCommandScheduler>());

        services.AddSingleton<DeterministicCommandVoiceHandler>();
        services.AddSingleton<IVoiceTextHandler>(provider =>
            provider.GetRequiredService<DeterministicCommandVoiceHandler>());

        return services;
    }
}
