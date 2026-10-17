namespace OmniPower
{
    /// Writes each line to every underlying log, e.g. the console and the log file at once.
    public sealed class TeeLog : ILog
    {
        private readonly ILog[] _logs;

        public TeeLog(params ILog[] logs)
        {
            _logs = logs;
        }

        public void Write(string message)
        {
            foreach (ILog log in _logs)
            {
                log.Write(message);
            }
        }
    }
}
