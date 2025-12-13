namespace OmniSync.Hub.Models
{
    public class FileSystemEntry
    {
        public string Name { get; set; }
        public string Path { get; set; } // Relative path from browse root
        public bool IsDirectory { get; set; }
        public long Size { get; set; } // For files
        public System.DateTime LastModified { get; set; }
    }
}
