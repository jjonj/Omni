# Guide: Writing Proper AI Integration Tests

This guide outlines best practices for writing robust integration tests for the Gemini CLI and its integration with the OmniSync Hub and Android client.

## 1. Sanity Checks First
Never assume the environment is ready. Always verify:
- **Session Existence:** Confirm you received a valid PID from the Hub after launching a CLI session.
- **AI Connectivity:** Verify the AI acknowledges the first command or finishes its startup mandate.
- **Basic Reply:** Check if the AI replies *anything* at all within a reasonable timeout before checking for specific content.

## 2. Robust Synchronization (Silence Detection)
The AI is non-deterministic and often uses **multiple turns** to complete a task (e.g., read file -> plan change -> apply change -> verify).
- **Avoid `FINISHED` dependence:** Don't stop your test at the first `FINISHED` status. The AI might immediately start another turn.
- **Use Silence Detection:** Wait for a period of inactivity (e.g., 5-10 seconds) where no new responses or status updates are received. This is the most reliable way to ensure a multi-turn task is truly complete.
- **Accumulate Content:** Always append chunks to a `full_response_text` buffer. Verification should happen on the aggregate content of all turns.

## 3. Context Management
AI context pollution is a common source of flaky tests.
- **Fresh Sessions:** Prefer starting a new session (`StartCliAtWorkspace`) for each major test run.
- **Clear Command:** Use the `/clear` command before starting a specific test scenario to wipe the conversation history within an active session.
- **Unique Test Data:** Use unique, randomly generated, or highly specific strings (e.g., `test_target_word_123`) instead of common words like "apple" or "hello" to ensure you aren't matching old context or pre-existing file content.

## 4. Verification Strategies
- **Keyword Matching:** Check for both the original and the new state (e.g., in a replacement test, look for the old word and the new word to verify a diff was shown).
- **Tool Call Verification:** Verify that the AI actually invoked the expected tools (e.g., look for "Tool Call: replace" in the broadcast log).
- **JSON Handling:** Remember that tool results are often JSON objects (like file diffs). Your verification logic should handle stringified JSON if checking the raw broadcast stream.

## 5. Implementation Pattern (Python)
Refer to `TestScripts/AIFeature/test_diff_display.py` for a reference implementation of the `wait_for_startup_completion` (silence detection) and sanity check pattern.

```python
async def wait_for_idle(self, timeout=120, silence_duration=10):
    start = time.time()
    while time.time() - start < timeout:
        # If we received content recently, reset the silence timer
        if time.time() - self.last_activity_time > silence_duration:
            return True # AI is idle
        await asyncio.sleep(1)
    return False # Timeout
```
