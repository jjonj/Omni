namespace OmniSync.Hub.Logic.Services;

public class CalendarEvent
{
    public string Summary { get; set; } = "No Title";
    public DateTime Start { get; set; }
    public bool IsAllDay { get; set; }
    public string? RRule { get; set; }
    public string Uid { get; set; } = string.Empty;
    public int Sequence { get; set; }
    public string? LastModified { get; set; }
    public string? Status { get; set; }
    public string? Description { get; set; }
    public string? Location { get; set; }
    public string? RecurrenceId { get; set; }
}
