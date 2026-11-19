using OmniZones.Model;

namespace OmniZones.Engine;

/// <summary>The modifier-key rules for dragging, kept pure so they can be unit tested.</summary>
public static class SnapPolicy
{
    /// <summary>
    /// Do zones engage for this drag? Default: always, and Shift suspends them.
    /// With <see cref="ZoneSettings.RequireShift"/>: only while Shift is held.
    /// </summary>
    public static bool IsEngaged(ZoneSettings s, bool shiftHeld) => s.RequireShift ? shiftHeld : !shiftHeld;

    /// <summary>Only a move from another actual zone can exchange its occupant. Floating and same-zone drops overlap.</summary>
    public static bool IsCrossZoneMove(string? originZone, RectD? originArea, string targetZone, RectD targetArea) =>
        originZone != null && originArea is { } area && (originZone != targetZone || area != targetArea);

    /// <summary>True when the pointer has reached the physical top edge of the current monitor.</summary>
    public static bool IsAtTopEdge(double cursorY, double monitorTop) => cursorY <= monitorTop;

    /// <summary>What dropping on an occupied zone does, given whether the hold key is down.</summary>
    public static OccupiedAction ResolveOccupied(ZoneSettings s, bool holdKeyHeld) =>
        s.SimpleMode ? OccupiedAction.Stack   // simple mode: no swap/stack choice and no hold key, windows just stack
        : holdKeyHeld && s.OccupiedHold != HoldKey.None
            ? (s.OccupiedDefault == OccupiedAction.Swap ? OccupiedAction.Stack : OccupiedAction.Swap)
            : s.OccupiedDefault;

    /// <summary>The scene rule (if any) triggered by dropping <paramref name="process"/> on <paramref name="zoneId"/>.</summary>
    public static SceneRule? FindScene(ZoneSettings s, ZoneLayout layout, string process, string zoneId)
    {
        if (s.SimpleMode) return null;
        return layout.Scenes.FirstOrDefault(r =>
            r.OnZoneId == zoneId && r.SwitchToLayoutId != layout.Id &&
            string.Equals(r.Process, process, StringComparison.OrdinalIgnoreCase) &&
            s.Layouts.Any(l => l.Id == r.SwitchToLayoutId));
    }

    /// <summary>Human text for the overlay's action chip.</summary>
    public static string DescribeDrop(ZoneSettings s, Zone zone, int occupants, OccupiedAction occupied, bool hasOrigin, bool scene, bool plainOnStack = false)
    {
        if (scene) return "Start scene";
        switch (ZoneEngine.KindOf(zone, s.SimpleMode))
        {
            case ZoneKind.Stack when plainOnStack && occupants > 0 && occupied == OccupiedAction.Swap:
                return hasOrigin ? "Swap places" : "Replace";
            case ZoneKind.Stack: return $"Join stack ({occupants + 1})";
            case ZoneKind.Peek: return "Dock to edge";
        }
        if (occupants == 0) return "Snap here";
        return occupied == OccupiedAction.Stack ? "Stack on top" : hasOrigin ? "Swap places" : "Replace";
    }
}
