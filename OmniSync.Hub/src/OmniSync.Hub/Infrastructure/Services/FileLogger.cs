using Microsoft.Extensions.Logging;
using System;
using System.IO;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class FileLoggerProvider : ILoggerProvider
    {
        private readonly string _path;
        public FileLoggerProvider(string path) => _path = path;
        public ILogger CreateLogger(string categoryName) => new FileLogger(_path, categoryName);
        public void Dispose() { }
    }

    public class FileLogger : ILogger
    {
        private readonly string _path;
        private readonly string _category;
        private static readonly object _lock = new();

        public FileLogger(string path, string category)
        {
            _path = path;
            _category = category;
        }

        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            var message = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{logLevel}] [{_category}] {formatter(state, exception)}";
            if (exception != null) message += Environment.NewLine + exception;
            
            lock (_lock)
            {
                File.AppendAllText(_path, message + Environment.NewLine);
            }
        }
    }
}
