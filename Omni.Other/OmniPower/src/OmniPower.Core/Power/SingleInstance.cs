using System.Threading;

namespace OmniPower
{
    public static class SingleInstance
    {
        /// Returns the held mutex, or null when another launcher is already running the same action.
        public static Mutex? TryAcquire(string name)
        {
            var mutex = new Mutex(true, name, out bool created);
            if (created)
            {
                return mutex;
            }

            mutex.Dispose();
            return null;
        }
    }
}
