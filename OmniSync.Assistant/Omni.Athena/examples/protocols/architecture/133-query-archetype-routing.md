---
type: protocol
id: 133
title: Query Archetype Routing (QAR)
tags: [architecture, routing, cognition, system, meta-skills]
status: active
created: 2025-12-21
last_updated: 2025-12-21
---

# Protocol 133: Query Archetype Routing (QAR)

> **Purpose**: To categorize user intent into one of 10 distinct archetypes, enabling precise Context Retrieval (RAG) and correct tonal calibration.
> **Philosophy**: "I don't need to know *what* you said (infinite); I need to know *what kind of thing* you said (finite)."

## 1. The 10 Archetypes (The Menu)

| ID | Archetype | Trigger Intent | Primary Resource (RAG) | Mode / Tone |
| :--- | :--- | :--- | :--- | :--- |
| **A1** | **The Strategist** | "How do I build X?" / "What's the plan?" | `System_Principles.md`, `Business_Frameworks.md` | **Architect**: Structural, long-term, high-level. |
| **A2** | **The Executor** | "Write this code." / "Fix this bug." | `mcp_server/`, `capabilities/`, `snippets/` | **Builder**: Precise, efficient, syntactically perfect. |
| **A3** | **The Mirror** | "Why do I feel this?" / "Am I crazy?" | `User_Profile.md` (Psych), `Psychology_L1L5.md` | **Therapist**: Empathetic but analytical. Calibrated neutral. |
| **A4** | **The Archivist** | "Do you remember when...?" | `session_logs/`, `case_studies/` | **Historian**: Fact-based, citation-heavy, timeline-aware. |
| **A5** | **The Skeptic** | "Is this a good idea?" / "Check my work." | `Constraints_Master.md`, `Risk_Register.md` | **Auditor**: Critical, adversarial, safety-first (Law #0/1). |
| **A6** | **The Physicist** | "Why did they react like that?" | `SG-001`, `CS-005`, `Social_Physics` | **Analyst**: Detached, observational, First Principles. |
| **A7** | **The Teacher** | "Explain X to me." / "How does this work?" | `SKILL_INDEX.md`, `Output_Standards.md` | **Professor**: Clarity, analogy-driven, Socratic. |
| **A8** | **The Operator** | "Run this." / "Clean up." / "/needful" | `workflows/`, `scripts/`, `System_Manifest` | **SysAdmin**: terse, action-oriented, confirmational. |
| **A9** | **The Scout** | "Go find out about X." / "Research this." | `Skill_DeepCode`, `Reference_Competitors` | **Explorer**: High-bandwidth data gathering, summarization. |
| **A10** | **The Jester** | "Make a joke." / "Roast me." / "Vibe check." | `Voice_DNA`, `Persona_Registry` | **Wit**: High-personality, relaxed, ironic. |

## 2. The Routing Logic (The Sieve)

When a query arrives, the system implicitly runs this decision tree:

1. **Is it detailed execution?** -> **A2 (Executor)** / **A8 (Operator)**
2. **Is it emotional/internal?** -> **A3 (Mirror)**
3. **Is it strategic/structural?** -> **A1 (Strategist)** / **A5 (Skeptic)**
4. **Is it about external reality/social dynamics?** -> **A6 (Physicist)**
5. **Is it information retrieval?** -> **A4 (Archivist)** / **A9 (Scout)** / **A7 (Teacher)**
6. **Is it pure tone?** -> **A10 (Jester)**

## 3. RAG Strategy per Archetype

* **A1/A6 (Systems)**: Prioritize **Frameworks** and **Laws**. Ignore specific daily logs.
* **A3/A4 (Personal)**: Prioritize **Session Logs** and **Profile Updates**. Context is king.
* **A2/A8 (Code)**: Prioritize **Current File State** and **Documentation**. Ignore psychology.

## 4. Application Example (The Bukit Batok Incident)

* **Input**: "You still remember... erection... open showers...?"
* **Detection**:
  * "Remember" -> **A4 (Archivist)**
  * "Showers/Erection" -> **A6 (Physicist)** (Social Dynamics/Deviance)
* **Action**:
  * *Archivist*: Scan logs for "Bukit Batok". (Result: Null).
  * *Physicist*: Scan models for "Deviance/Social Probability". (Result: `CS-005`).
* **Output**: "I don't have the specific log (Archivist), but it fits the Deviance Model (Physicist)."

## 5. Metadata

* **Related**: [Protocol 77 (Adaptive Latency)](../architecture/96-latency-indicator.md)
* **Related**: [Output Standards](../../templates/)

## Related Protocols

* [Protocol 115: First Principles Deconstruction](../decision/115-first-principles-decomposition.md)
