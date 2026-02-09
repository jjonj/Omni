# Specification - Fix AI Message Queuing during Launch

## Overview
The OmniSync Hub uses a named pipe queuing system to send messages to the Gemini CLI. Currently, messages sent while the CLI is still initializing (during launch) are being lost. This causes the `roundtrip_test.py` to fail as it expects a reply to an initial message that never arrives at the CLI. Additionally, we will investigate if spawning CLI instances as non-admin (since the Hub runs as administrator) resolves any potential permission or named pipe communication mismatches.

## Functional Requirements
1. **Reliable Initialization Messaging:** Ensure that any messages sent via the Hub's queuing system while the CLI is launching are correctly delivered once the pipe connection is fully established.
2. **Non-Admin CLI Spawning:** Modify the Hub's process spawning logic to ensure Gemini CLI instances are launched as the standard user, avoiding elevated (administrator) privileges even when the Hub itself is running as administrator.
3. **Queue Integrity:** Verify that the internal queue in `AiCliService` is correctly managed and that messages are only dequeued and written to the named pipe after a successful `ConnectAsync` event.

## Non-Functional Requirements
1. **Verification:** The fix must be verified by a successful and reliable run of `TestScripts/AIFeature/roundtrip_test.py`.
2. **Logging:** Maintain or enhance logging in `pipe_debug.log` to clearly trace the process from launch, through queuing, to successful pipe write.

## Acceptance Criteria
1. `roundtrip_test.py` passes consistently without timing out on the initial message.
2. `pipe_debug.log` confirms that messages queued during the `LAUNCH` phase are successfully written to the pipe after the `CONNECT SUCCESS` event.
3. Confirmation that CLI instances are no longer running with administrative privileges.

## Out of Scope
- Optimizing general AI response latency.
- Modifying the Gemini CLI's internal message processing logic (beyond named pipe reception).
