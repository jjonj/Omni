# Protocol 210: Data Lifecycle Strategy (The Metabolic Cycle)

> **Goal**: Prevent "Digital Hoarding" (Write-Only Memory) via structured accumulation and aggressive periodic refactoring.
> **Philosophy**: "Accumulate 1000, keep the best 200, repeat."

---

## 1. The Core Loop

We do not aim for a static "clean" state. We aim for a breathing cycle of expansion and contraction.

**The Cycle**:

1. **Accumulation Phase (0 - 1,000 Sessions)**:
    * **Goal**: Maximize raw data capture.
    * **Action**: Log everything. Create detailed case studies. Do not optimize for brevity; optimize for *capture*.
    * **Logic**: You cannot synthesize what you did not record.

2. **The Deep Refactor Event (Trigger: Session #1000)**:
    * **Goal**: Signal Extraction (Compression).
    * **Action**: Pause new inputs. Audit the `.context` directory.
    * **The 20% Rule**:
        * Keep the top 20% of Case Studies (High Signal).
        * Merge duplicate Protocols.
        * Compress 1,000 Session Logs into ~50 "Epoch Summaries".
        * Archive the rest (Cold Storage).

3. **The Fine-Tune Event (Trigger: Post-Refactor)**:
    * **Goal**: Crystallization.
    * **Action**: Use the refined 20% dataset to fine-tune the next-gen local model ("the author Prime").

---

## 2. Quantitative Milestones

| Milestone | Metric | Action |
| :--- | :--- | :--- |
| **Current** | ~100 Sessions | **Accumulate**. Focus on Case Study creation. |
| **Checkpoint 1** | 250 Sessions | **Light Pruning**. Run `analyze_orphan_files.py`. |
| **Checkpoint 2** | 500 Sessions | **Graph Review**. Check for node density. |
| **THE EVENT** | **1000 Sessions** | **DEEP REFACTOR**. Stop work for 3 days. Compress to 20%. |

---

## 3. Operational Directives

* **Don't Stress the Mess**: During the Accumulation Phase, file proliferation is *good*. It means we are capturing resolution.
* **Trust the Search**: Supabase semantic search (§0.7.1) abstracts away the file count. We find meaning, not filenames.
* **Kill the Mediocre**: When we refactor, we kill average sessions. We only keep:
    1. **Trauma/Failure** (Learning moments).
    2. **Breakthroughs** (Paradigm shifts).
    3. **Standard Operating Procedures** (Protocols).

---

## Tagging

# data-strategy #lifecycle #refactor #long-term
