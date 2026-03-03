using System;

namespace OmniSync.Hub.Models
{
    public class BookProgress
    {
        public string BookPath { get; set; } = string.Empty;
        public string Position { get; set; } = string.Empty;
        public DateTime LastUpdated { get; set; } = DateTime.UtcNow;
    }
}
