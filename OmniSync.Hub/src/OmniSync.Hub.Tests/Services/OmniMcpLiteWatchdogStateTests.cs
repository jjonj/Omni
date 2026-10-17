using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Tests.Services;

public class OmniMcpLiteWatchdogStateTests
{
    private readonly DateTimeOffset _start = new(2026, 10, 14, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void HealthyChecksNeverRestart()
    {
        var state = new OmniMcpLiteWatchdogState();

        for (int i = 0; i < 10; i++)
        {
            var result = state.Observe(true, _start.AddSeconds(10 * i));
            Assert.False(result.Restart);
            Assert.False(result.Recovered);
        }

        Assert.Equal(0, state.ConsecutiveFailures);
    }

    [Fact]
    public void ThreeConsecutiveFailuresTriggerOneRestart()
    {
        var state = new OmniMcpLiteWatchdogState();

        var first = state.Observe(false, _start);
        var second = state.Observe(false, _start.AddSeconds(10));
        var third = state.Observe(false, _start.AddSeconds(20));
        var fourth = state.Observe(false, _start.AddSeconds(30));

        Assert.True(first.FirstFailure);
        Assert.False(first.Restart);
        Assert.False(second.Restart);
        Assert.True(third.Restart);
        Assert.False(fourth.Restart);
        Assert.Equal(4, state.ConsecutiveFailures);
    }

    [Fact]
    public void SuccessResetsConsecutiveFailuresAndReportsRecovery()
    {
        var state = new OmniMcpLiteWatchdogState();

        state.Observe(false, _start);
        state.Observe(false, _start.AddSeconds(10));
        var recovered = state.Observe(true, _start.AddSeconds(20));
        var freshFailure = state.Observe(false, _start.AddSeconds(30));

        Assert.True(recovered.Recovered);
        Assert.Equal(1, state.ConsecutiveFailures);
        Assert.True(freshFailure.FirstFailure);
        Assert.False(freshFailure.Restart);
    }

    [Fact]
    public void RestartAttemptsHaveCooldownEvenWhenFailuresContinue()
    {
        var state = new OmniMcpLiteWatchdogState();

        state.Observe(false, _start);
        state.Observe(false, _start.AddSeconds(10));
        Assert.True(state.Observe(false, _start.AddSeconds(20)).Restart);

        Assert.False(state.Observe(false, _start.AddSeconds(79)).Restart);
        Assert.True(state.Observe(false, _start.AddSeconds(80)).Restart);
        Assert.False(state.Observe(false, _start.AddSeconds(90)).Restart);
    }

    [Fact]
    public void RecoveryDoesNotBypassRestartCooldown()
    {
        var state = new OmniMcpLiteWatchdogState();

        state.Observe(false, _start);
        state.Observe(false, _start.AddSeconds(10));
        Assert.True(state.Observe(false, _start.AddSeconds(20)).Restart);
        Assert.True(state.Observe(true, _start.AddSeconds(25)).Recovered);

        state.Observe(false, _start.AddSeconds(30));
        state.Observe(false, _start.AddSeconds(40));
        Assert.False(state.Observe(false, _start.AddSeconds(50)).Restart);
        Assert.True(state.Observe(false, _start.AddSeconds(80)).Restart);
    }
}
