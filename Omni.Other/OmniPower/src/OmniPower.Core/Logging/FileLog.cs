using System;
using System.IO;

namespace OmniPower
{
    public interface ILog
    {
        void Write(string message);
    }

    /// Appends timestamped lines to %LOCALAPPDATA%\OmniPower\logs\<name>.log.
    public sealed class FileLog : ILog
    {
        private static readonly object Gate = new object();
        private readonly string _path;

        public FileLog(string name)
        {
            string directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OmniPower", "logs");
            Directory.CreateDirectory(directory);
            _path = Path.Combine(directory, name + ".log");
        }

        public string FilePath => _path;

        public void Write(string message)
        {
            string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " " + message + Environment.NewLine;
            lock (Gate)
            {
                File.AppendAllText(_path, line);
            }
        }
    }
}
