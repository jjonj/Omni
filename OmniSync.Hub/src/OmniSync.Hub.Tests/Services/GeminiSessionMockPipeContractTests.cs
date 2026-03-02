using System.IO.Pipes;
using System.Text;
using Microsoft.Extensions.Logging.Abstractions;
using OmniSync.Hub.Infrastructure.Services;
using Xunit;

namespace OmniSync.Hub.Tests.Services
{
    public class GeminiSessionMockPipeContractTests
    {
        [Fact]
        public async Task GeminiSession_ParsesResponseEvent_UsingPipeContract()
        {
            int pid = Random.Shared.Next(300000, 399999);
            using var server = new NamedPipeServerStream(
                $"omni-gemini-cli-{pid}",
                PipeDirection.InOut,
                1,
                PipeTransmissionMode.Byte,
                PipeOptions.Asynchronous);

            var waitForConnection = server.WaitForConnectionAsync();
            var responseTcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

            using var session = new GeminiSession(
                pid,
                DateTime.Now,
                NullLogger.Instance,
                false,
                (_, text, _, _, _, _) => responseTcs.TrySetResult(text),
                (_, _, _, _) => { },
                (_, _, _, _, _, _, _, _, _, _) => { });

            bool connected = await session.ConnectAsync(2000);
            Assert.True(connected);
            await WaitWithTimeout(waitForConnection, TimeSpan.FromSeconds(3));

            using var writer = new StreamWriter(server, Encoding.UTF8, 4096, true) { AutoFlush = true };
            await writer.WriteLineAsync("{\"type\":\"response\",\"text\":\"pong from cli mock\"}");

            var completed = await Task.WhenAny(responseTcs.Task, Task.Delay(3000));
            Assert.Same(responseTcs.Task, completed);
            Assert.Equal("pong from cli mock", await responseTcs.Task);
        }

        private static async Task WaitWithTimeout(Task task, TimeSpan timeout)
        {
            var completed = await Task.WhenAny(task, Task.Delay(timeout));
            Assert.Same(task, completed);
            await task;
        }

    }
}
