using System;

namespace OmniSync.Hub.Models
{
    public class PcgObjectState
    {
        public string WorldId { get; set; } = "";
        public string PositionHash { get; set; } = "";
        public float X { get; set; }
        public float Y { get; set; }
        public string Data { get; set; } = ""; // JSON or raw string representing the changes
        public bool IsExclusion { get; set; }
        public DateTime LastModified { get; set; }
    }
}