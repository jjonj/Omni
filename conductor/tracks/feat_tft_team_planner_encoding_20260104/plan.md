# Plan: TFT Team Planner Encoding

## Goal
Implement a parser and generator for the "Fixed-Width Chunked ID Serialization" format for TFT Set 16 team planner codes.

## Source
Based on `D:\SSDProjects\Omni\OmniSync.Web\www\assets\tft\Teamplannerencoding.txt`.

## Specification
- **Header**: `02` (2 chars)
- **Payload**: 30 chars (10 slots x 3 hex chars/12 bits)
  - Slot 1: Chars 3-5
  - ...
  - Slot 10: Chars 30-32 (implied index adjustment)
- **Padding**: Unused slots filled with `000`.
- **Trailer**: `TFTSet16` (9 chars)

**Total Length**: 2 + 30 + 9 = 41 characters (approx, verify exact length based on slot count).
Actually:
Header (2) + Payload (30) + Trailer (9) = 41 characters?
Wait, if slots are 3 chars each, 10 slots = 30 chars.
Indices:
0,1: Header
2-31: Payload (10 * 3)
32-40: Trailer (9 chars? "TFTSet16" is 8 chars. "TFTSet16."? Text says 9 chars.)
Text says: "Trailer (9 chars): TFTSet16." - The period might be part of it or a typo in my reading. "TFTSet16" is 8.
Let's assume "TFTSet16." including the dot, or maybe I should look for examples if available.
Actually, let's stick to the prompt description.

## Tasks
- [x] Create a utility class/module TeamPlannerCode.js (or Python equivalent if backend needed) to parse/generate these codes. 473a241
- [x] Map the "Verified Set 16 Champion IDs" provided. 473a241
- [x] Add UI in TFT.html to import/export these codes. 08c2a91
