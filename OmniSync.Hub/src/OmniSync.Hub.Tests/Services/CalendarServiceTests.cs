using Moq;
using Moq.Protected;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Monitoring;
using Xunit;
using System;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Hosting;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Tests.Services
{
    public class CalendarServiceTests
    {
        private readonly Mock<HubMonitorService> _monitorServiceMock;
        private readonly Mock<ILogger<CalendarService>> _loggerMock;
        private readonly Mock<HttpMessageHandler> _httpMessageHandlerMock;
        private readonly Mock<HubSettingsService> _settingsServiceMock;

        public CalendarServiceTests()
        {
            var hubMonitorLogger = new Mock<ILogger<HubMonitorService>>();
            var appLifetimeMock = new Mock<IHostApplicationLifetime>();
            _monitorServiceMock = new Mock<HubMonitorService>(appLifetimeMock.Object, hubMonitorLogger.Object);
            _loggerMock = new Mock<ILogger<CalendarService>>();
            _httpMessageHandlerMock = new Mock<HttpMessageHandler>();
            _settingsServiceMock = new Mock<HubSettingsService>(new Mock<ILogger<HubSettingsService>>().Object);
        }

        [Fact]
        public async Task RefreshCalendarAsync_ShouldParseIcsCorrectly()
        {
            // Arrange
            var today = DateTime.Today.ToString("yyyyMMdd");
            var icsContent = $@"BEGIN:VCALENDAR
BEGIN:VEVENT
DTSTART:{today}T120000Z
SUMMARY:Test Event
END:VEVENT
END:VCALENDAR";

            _httpMessageHandlerMock.Protected()
                .Setup<Task<HttpResponseMessage>>(
                    "SendAsync",
                    ItExpr.IsAny<HttpRequestMessage>(),
                    ItExpr.IsAny<CancellationToken>()
                )
                .ReturnsAsync(new HttpResponseMessage
                {
                    StatusCode = HttpStatusCode.OK,
                    Content = new StringContent(icsContent)
                });

            var httpClient = new HttpClient(_httpMessageHandlerMock.Object);
            _settingsServiceMock.SetupGet(s => s.Settings).Returns(new HubSettings { CalendarUrl = "https://calendar.test/test.ics" });
            var service = new CalendarService(httpClient, _monitorServiceMock.Object, _settingsServiceMock.Object, _loggerMock.Object);

            // Act
            await service.RefreshCalendarAsync();
            var events = service.GetTodayEvents();

            // Assert
            Assert.Single(events);
            Assert.Equal("Test Event", events[0].Summary);
            // Check time (UTC 12:00 -> Local will depend on timezone but should be same day)
            Assert.Equal(DateTime.Today, events[0].Start.Date);
        }

        [Fact]
        public async Task GetNextEvent_ShouldReturnSoonestFutureEvent()
        {
            // Arrange
            var today = DateTime.Today.ToString("yyyyMMdd");
            // One event in the past, one in the future
            var pastTime = DateTime.UtcNow.AddHours(-1).ToString("HHmmss");
            var futureTime = DateTime.UtcNow.AddHours(1).ToString("HHmmss");

            var icsContent = $@"BEGIN:VCALENDAR
BEGIN:VEVENT
DTSTART:{today}T{pastTime}Z
SUMMARY:Past Event
END:VEVENT
BEGIN:VEVENT
DTSTART:{today}T{futureTime}Z
SUMMARY:Future Event
END:VEVENT
END:VCALENDAR";

            _httpMessageHandlerMock.Protected()
                .Setup<Task<HttpResponseMessage>>(
                    "SendAsync",
                    ItExpr.IsAny<HttpRequestMessage>(),
                    ItExpr.IsAny<CancellationToken>()
                )
                .ReturnsAsync(new HttpResponseMessage
                {
                    StatusCode = HttpStatusCode.OK,
                    Content = new StringContent(icsContent)
                });

            var httpClient = new HttpClient(_httpMessageHandlerMock.Object);
            _settingsServiceMock.SetupGet(s => s.Settings).Returns(new HubSettings { CalendarUrl = "https://calendar.test/test.ics" });
            var service = new CalendarService(httpClient, _monitorServiceMock.Object, _settingsServiceMock.Object, _loggerMock.Object);

            // Act
            await service.RefreshCalendarAsync();
            var next = service.GetNextEvent();

            // Assert
            Assert.NotNull(next);
            Assert.Equal("Future Event", next.Summary);
        }
    }
}
