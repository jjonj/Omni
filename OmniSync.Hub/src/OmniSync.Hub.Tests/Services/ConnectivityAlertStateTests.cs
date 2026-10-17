using System.Text.Json;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Tests.Services;

public class ConnectivityAlertStateTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-16T00:00:00Z");
    private static readonly ConnectivityAlert Problem = new(true, "Tailscale connection lost", "Offline");

    [Fact]
    public void ConfirmsOutageWithoutRepeatingAndResolvesOnce()
    {
        var state = new ConnectivityAlertState();
        Assert.Null(state.Observe(Problem, Now));
        Assert.Equal(Problem, state.Observe(Problem, Now.AddSeconds(5)));
        Assert.Null(state.Observe(Problem, Now.AddSeconds(30)));
        Assert.Null(state.Observe(Problem, Now.AddSeconds(35)));
        Assert.Null(state.Observe(Problem, Now.AddHours(1)));
        Assert.Null(state.Observe(null, Now.AddHours(1).AddSeconds(5)));
        Assert.False(state.Observe(null, Now.AddHours(1).AddMinutes(2).AddSeconds(5))!.Active);
        Assert.Null(state.Observe(null, Now.AddHours(1).AddMinutes(3)));
        Assert.Null(state.Observe(Problem, Now.AddHours(2)));
        Assert.Equal(Problem, state.Observe(Problem, Now.AddHours(2).AddSeconds(5)));
    }

    [Fact]
    public void SingleFailedSampleDoesNotAlertAndMaintenanceDoesNotClaimRecovery()
    {
        var state = new ConnectivityAlertState();
        Assert.Null(state.Observe(Problem, Now));
        Assert.Null(state.Observe(null, Now.AddSeconds(5)));
        Assert.Null(state.Observe(Problem, Now.AddSeconds(10)));
        Assert.NotNull(state.Observe(Problem, Now.AddSeconds(15)));
        Assert.Null(state.Observe(null, Now.AddSeconds(20), paused: true));
        Assert.Null(state.Observe(null, Now.AddSeconds(25)));
        Assert.False(state.Observe(null, Now.AddMinutes(3))!.Active);
    }

    [Theory]
    [InlineData(0, false)]
    [InlineData(1, false)]
    [InlineData(2, false)]
    [InlineData(3, true)]
    public void PublicAlertRequiresSeparateFailedProbeRounds(int publicFailures, bool confirmed)
    {
        using var document = JsonDocument.Parse(JsonSerializer.Serialize(new {
            checkedAt = Now, localHealthy = true, publicHealthy = false, publicFailures,
            tailscale = new { state = "Running", online = true, authRequired = false }
        }));
        var problem = ConnectivityAlertState.Classify(document.RootElement, Now)!;
        Assert.Equal(confirmed, problem.Confirmed);
        var state = new ConnectivityAlertState();
        Assert.Null(state.Observe(problem, Now));
        var alert = state.Observe(problem, Now.AddSeconds(5));
        Assert.Equal(confirmed, alert != null);
        if (!confirmed) Assert.Null(state.Observe(problem, Now.AddMinutes(10)));
    }

    [Fact]
    public void BriefRecoveryAndPendingDegradationDoNotResetAnExistingIncident()
    {
        var state = new ConnectivityAlertState();
        state.Observe(Problem, Now);
        Assert.NotNull(state.Observe(Problem, Now.AddSeconds(5)));
        Assert.Null(state.Observe(null, Now.AddMinutes(1)));
        Assert.Null(state.Observe(Problem with { Confirmed = false }, Now.AddMinutes(2)));
        Assert.Null(state.Observe(Problem, Now.AddMinutes(3)));
        Assert.Null(state.Observe(Problem, Now.AddMinutes(3).AddSeconds(5)));
        Assert.Null(state.Observe(null, Now.AddMinutes(4)));
        Assert.Null(state.Observe(null, Now.AddMinutes(5)));
        Assert.False(state.Observe(null, Now.AddMinutes(6))!.Active);
    }

    [Theory]
    [InlineData("Running", true, false, true, null)]
    [InlineData("NeedsLogin", false, true, true, "authorization")]
    [InlineData("NeedsMachineAuth", false, true, false, "authorization")]
    [InlineData("Stopped", false, false, true, "connection lost")]
    [InlineData("Running", true, false, false, "Public remote access")]
    public void DistinguishesAuthorizationServiceAndPublicTransportFailures(string transport, bool online, bool auth, bool publicHealthy, string? title)
    {
        using var document = JsonDocument.Parse(JsonSerializer.Serialize(new {
            checkedAt = Now, paused = false, localHealthy = true, publicHealthy,
            tailscale = new { state = transport, online, authRequired = auth }
        }));
        var alert = ConnectivityAlertState.Classify(document.RootElement, Now);
        if (title == null) Assert.Null(alert);
        else Assert.Contains(title, alert!.Title);
    }

    [Fact]
    public void StaleHeartbeatAlertsEvenWhenLastStateWasHealthyOrPaused()
    {
        using var document = JsonDocument.Parse(JsonSerializer.Serialize(new {
            checkedAt = Now.AddMinutes(-2), paused = true, localHealthy = true, publicHealthy = true
        }));
        Assert.Contains("monitoring stopped", ConnectivityAlertState.Classify(document.RootElement, Now)!.Title);
    }
}
