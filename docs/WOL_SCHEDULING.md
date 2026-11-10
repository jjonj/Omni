# Phone-owned Wake-on-LAN schedules

Android owns the AlarmManager alarm. Once the phone confirms a schedule, Windows may shut down. The phone must remain powered on and have network access at firing time.

## Android

Dashboard > Wake PC supports Wake now, +30m / +1h / +4h / +6h, date/time picker, pending schedule list, edit and cancel. Schedules from PC agents are also visible. Edit is deliberately restricted to phone-origin schedules, to keep PC run receipts consistent.

The one-time requested 12:37 Copenhagen wake remains preserved. The phone persists alarms across restart/update. An Android alarm notification does not guarantee the PC actually woke.

## PC agent (via OMCP shell)

From D:/SSDProjects/Omni:

    python OmniSync.Android/scripts/schedule_wol.py --after 4h
    python OmniSync.Android/scripts/schedule_wol.py --at 2026-11-11T08:30
    python OmniSync.Android/scripts/schedule_wol.py --now
    python OmniSync.Android/scripts/schedule_wol.py --list

Absolute times without offset use the PC's local timezone. Include +01:00 for explicit timezone. The helper reads the private Hub key locally, verifies that the phone is connected and advertising wake.pc, then submits a request through /api/voice-inbox. Rust OmniHub sends ScheduleAssistantCommand over SignalR; Android persists and acknowledges it.

An HTTP Accepted receipt is only a dispatch request. Check --list and verify the exact run ID reaches Scheduled (phone acknowledgment) before turning off Windows. On unknown results, reconcile by requestId before retrying.

## Current limitations

- Scheduling requires the Android phone connected to the Hub at request time. Once scheduled, Hub availability is no longer necessary.
- Hub's /api/assistant/runs/:runId/cancel only cancels the Hub receipt; it does not cancel the phone alarm. To cancel for real, use Android UI.
- Android cancelling a PC-origin alarm reports back when connected; offline cancellation still removes the phone alarm but Hub history may be stale.
- These are one-off alarms, not recurring schedules.
