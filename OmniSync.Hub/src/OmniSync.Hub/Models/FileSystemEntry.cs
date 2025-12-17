namespace OmniSync.Hub.Models
{
    public class FileSystemEntry
    {
        public string Name { get; set; }
        public string Path { get; set; } // Can be absolute path or relative, depending on context
        public bool IsDirectory { get; set; }
        public long Size { get; set; } // For files
        public System.DateTime LastModified { get; set; }
        public string EntryType { get; set; } // "Drive", "Directory", "File"
    }
}
