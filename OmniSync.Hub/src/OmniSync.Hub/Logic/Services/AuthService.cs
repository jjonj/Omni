namespace OmniSync.Hub.Logic.Services
{
    public class AuthService
    {
        private readonly string _apiKey;

        public AuthService(string apiKey)
        {
            _apiKey = apiKey;
        }

        public bool Validate(string providedApiKey)
        {
            // Simple string comparison for now.
            // In the future, this could be a more secure comparison.
            return !string.IsNullOrEmpty(providedApiKey) && _apiKey == providedApiKey;
        }
    }
}
