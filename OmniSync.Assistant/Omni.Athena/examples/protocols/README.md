# Athena Protocols

> [!NOTE]
> **These are reference implementations from Winston's personal system.**
>
> Use them to understand the *pattern* — not as prescriptions. Your protocols will reflect your own context, domain, and decision history.
>
> See [Creating Your Own Protocols](#creating-new-protocols) to build yours.

Protocols are reusable thinking patterns that standardize how Athena reasons about specific domains.

## Featured Protocols

| ID | Name | Category | Purpose |
|----|------|----------|---------|
| **77** | [Adaptive Latency](examples/protocols/architecture/77-adaptive-latency-architecture.md) | Architecture | Scale reasoning depth to query complexity |
| **96** | [Latency Indicator](examples/protocols/architecture/96-latency-indicator.md) | Architecture | Append [Λ+XX] to show cognitive effort |
| **98** | [Strategic Depth Theory](examples/protocols/architecture/98-depth-vs-width-theory.md) | Architecture | When to go deep vs. wide |
| **130** | [Vibe Coding](examples/protocols/workflow/130-vibe-coding.md) | Workflow | Iterative UI development by "feel" |
| **133** | [Query Archetype Routing](examples/protocols/architecture/133-query-archetype-routing.md) | Architecture | Route queries to optimal processing paths |
| **158** | [Entity Lookup First](examples/protocols/architecture/158-entity-lookup-before-analysis.md) | Architecture | Always lookup entities before analyzing |
| **159** | [Verification Before Claim](examples/protocols/architecture/159-verification-before-claim.md) | Research | Verify facts before stating them |
| **168** | [Context-Driven Development](examples/protocols/architecture/168-context-driven-development.md) | Engineering | Let context guide implementation |
| **200** | [Feature Context Persistence](examples/protocols/architecture/200-feature-context-persistence.md) | Architecture | Track features across sessions |
| **202** | [Recovery Patterns](examples/protocols/architecture/202-recovery-patterns.md) | Architecture | Graceful degradation strategies |
| **408** | [Autonomous Contribution Engine](examples/protocols/workflow/408-autonomous-contribution-engine.md) | Workflow | Transform user insights into contributions |

## Categories

### 🏗️ Architecture (23 protocols)

System design, token management, context handling.

[Browse Architecture Protocols →](architecture/)

### ⚙️ Engineering (16 protocols)  

Code patterns, UI development, implementation standards.

[Browse Engineering Protocols →](engineering/)

### 🧭 Decision (5 protocols)

Decision frameworks, reasoning patterns, strategic analysis.

[Browse Decision Protocols →](decision/)

### 🔬 Research (3 protocols)

Deep investigation, fact-checking, source validation.

[Browse Research Protocols →](research/)

### 🎯 Strategy (3 protocols)

Business strategy, competitive positioning, market entry.

[Browse Strategy Protocols →](strategy/)

### ✅ Verification (2 protocols)

Testing, validation, quality assurance patterns.

[Browse Verification Protocols →](verification/)

### 📋 Workflow (10 protocols)

Session management, automation, process optimization.

[Browse Workflow Protocols →](workflow/)

### 🧠 Meta (1 protocol)

Protocols about protocols.

[Browse Meta Protocols →](meta/)

## Protocol Format

Each protocol follows this structure:

```markdown
---
id: 77
name: Adaptive Latency Architecture
category: architecture
status: active
---

# Protocol 77: Adaptive Latency Architecture

## Purpose
[What problem this solves]

## When to Use
[Trigger conditions]

## Implementation
[Step-by-step process]

## Examples
[Concrete usage examples]
```

## Creating New Protocols

Use the [protocol template](examples/templates/protocol_template.md) to create new protocols.

Assign the next available ID and add an entry to this index.
