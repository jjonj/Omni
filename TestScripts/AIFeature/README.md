# AI Feature Tests

Integration tests for the Gemini CLI and OmniSync AI services.

## Core Integration
- `roundtrip_test.py`: Verifies the full loop (Hub -> Pipe -> Gemini -> Pipe -> Hub).
- `multi_instance_test.py`: Validates that the Hub can manage multiple concurrent AI sessions.
- `commands_test.py`: Tests built-in AI commands and tool-calling logic.

## Formatting & UI
- `test_content_formatting.py`: Verifies markdown and code block rendering.
- `test_diff_display.py`: Specifically tests the generation and display of code diffs.
- `test_special_messages.py`: Tests system-level messages like thinking indicators.

## Specialized Tests
- `test_hookin_flow.py`: Tests the logic for "hooking into" existing workspace files.
- `test_android_ai_message.py`: Verifies that messages sent from Android are correctly processed by the Hub.

## Maintenance
- `cleanup_gemini_windows.py`: Force-kills all Gemini processes and resets the environment.
- `generate_mapping.js`: Generates ID-to-Name mappings for system UI elements.

---
**Note:** For a detailed guide on writing these tests, see [AI_TESTING_GUIDE.md](./AI_TESTING_GUIDE.md).
