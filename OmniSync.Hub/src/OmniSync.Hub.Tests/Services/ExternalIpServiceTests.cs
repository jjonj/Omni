using OmniSync.Hub.Logic.Services;
using Xunit;

namespace OmniSync.Hub.Tests.Services
{
    public class ExternalIpServiceTests
    {
        [Theory]
        [InlineData("85.80.233.70", "85.80.233.70")]
        [InlineData("  85.80.233.70\n", "85.80.233.70")]
        [InlineData("8.8.8.8", "8.8.8.8")]
        public void NormalizeExternalIp_KeepsUsableAddresses(string raw, string expected)
        {
            Assert.Equal(expected, ExternalIpService.NormalizeExternalIp(raw));
        }

        [Theory]
        [InlineData(null)]
        [InlineData("")]
        [InlineData("   ")]
        [InlineData("not an ip")]
        [InlineData("<html>rate limited</html>")]
        [InlineData("127.0.0.1")]
        [InlineData("0.0.0.0")]
        [InlineData("255.255.255.255")]
        [InlineData("169.254.1.1")]
        [InlineData("2001:db8::1")]
        public void NormalizeExternalIp_RejectsAnythingUnusable(string? raw)
        {
            Assert.Null(ExternalIpService.NormalizeExternalIp(raw));
        }

        [Theory]
        [InlineData("85.80.233.70", true)]
        [InlineData("8.8.8.8", true)]
        [InlineData("1.1.1.1", true)]
        [InlineData("192.168.1.37", false)]
        [InlineData("10.0.0.5", false)]
        [InlineData("172.16.4.4", false)]
        [InlineData("172.32.4.4", true)]
        [InlineData("127.0.0.1", false)]
        [InlineData("169.254.10.10", false)]
        [InlineData("nonsense", false)]
        public void IsPublicIpv4_SeparatesRoutableFromPrivateRanges(string ip, bool expected)
        {
            Assert.Equal(expected, ExternalIpService.IsPublicIpv4(ip));
        }
    }
}
