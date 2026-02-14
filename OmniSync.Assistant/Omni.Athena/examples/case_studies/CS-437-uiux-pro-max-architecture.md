---
{created: '2026-01-19', last_updated: '2026-01-30'}
created: 2026-01-19
last_updated: 2026-02-01
graphrag_extracted: true
---

last_updated: 2026-01-19
---

# CS-369: UI/UX Pro Max Skill Architecture

> **Filed**: 19 January 2026
> **Source**: [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)
> **Tags**: #architecture #ai-skills #frontend #design-system #bm25

---

## Executive Summary

A 18k-star open-source "AI skill" providing design intelligence for web/mobile UI. The architecture demonstrates a **best-practice pattern for creating domain-specific AI skills**: CSV databases + BM25 search + reasoning engine + persistence layer.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  USER REQUEST: "Build a landing page for my beauty spa"        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  MULTI-DOMAIN SEARCH (5 parallel searches via BM25)            │
│     • Product type matching (100 categories)                   │
│     • Style recommendations (57 styles)                        │
│     • Color palette selection (95 palettes)                    │
│     • Landing page patterns (24 patterns)                      │
│     • Typography pairing (56 font combinations)                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  REASONING ENGINE (ui-reasoning.csv)                           │
│     • 100 industry-specific rules                              │
│     • JSON decision conditions (`if_luxury: switch-to-glass`)  │
│     • Anti-patterns to avoid per industry                      │
│     • Severity ratings (HIGH/MEDIUM/LOW)                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN SYSTEM OUTPUT                                          │
│     Pattern + Style + Colors + Typography + Effects            │
│     + Anti-patterns + Pre-delivery checklist                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  PERSISTENCE (Master + Overrides Pattern)                      │
│     design-system/MASTER.md (Global SSoT)                      │
│     design-system/pages/[page].md (Overrides only)             │
└─────────────────────────────────────────────────────────────────┘
```

---

## Stolen Components (Integrated into Athena)

### 1. CLI Installer (`uipro-cli`)

**Pattern**: npm-based CLI tool that distributes skill files to multiple AI assistants (.agent/, .gemini/, .cursor/, etc.)

**Why it matters**: Cross-platform skill distribution. One source, multiple targets.

**Athena Integration**: Installed via `uipro init --ai antigravity`. Files now at:

- `.agent/workflows/ui-ux-pro-max.md`
- `.shared/ui-ux-pro-max/`

---

### 2. BM25 Search Engine (`core.py`)

**Pattern**: Pure Python BM25 implementation over CSV files. No external dependencies (no PyTorch, no sentence-transformers).

```python
class BM25:
    def __init__(self, k1=1.5, b=0.75):
        self.k1 = k1
        self.b = b
    
    def tokenize(self, text):
        text = re.sub(r'[^\w\s]', ' ', str(text).lower())
        return [w for w in text.split() if len(w) > 2]
    
    def score(self, query):
        # IDF-weighted term frequency scoring
```

**Why it matters**: Lightweight, portable, deterministic. No vector DB required for small-to-medium knowledge bases.

**Athena Observation**: This is an alternative to our `smart_search.py` for local-first search. Less semantic, but faster and no Supabase dependency.

---

### 3. Domain-Specific CSV Structure

| File | Rows | Purpose |
|------|------|---------|
| `ui-reasoning.csv` | 100 | Industry → Design System mapping |
| `styles.csv` | 57 | Style definitions (Glassmorphism, Brutalism, etc.) |
| `colors.csv` | 95 | Color palettes by industry |
| `typography.csv` | 56 | Font pairings with Google Fonts URLs |
| `landing.csv` | 24 | Page structure patterns |
| `ux-guidelines.csv` | 98 | Do/Don't rules with code examples |
| `stacks/*.csv` | 12 | Framework-specific best practices |

**Why it matters**: CSV is the ultimate portable format. Easy to edit, version, diff.

---

### 4. Reasoning Rules with JSON Decision Keys

```csv
UI_Category,Recommended_Pattern,Style_Priority,Color_Mood,Decision_Rules,Anti_Patterns
Beauty/Spa,Hero + Social Proof,Soft UI + Neumorphism,Soft pastels,"{""if_luxury"": ""add-gold-accents""}",Dark mode + Harsh animations
Fintech,Trust & Authority,Minimalism,Navy + Gold,"{""must_have"": ""security-badges""}",AI purple/pink gradients
```

**Why it matters**: The `Decision_Rules` column contains structured JSON that can trigger conditional behavior. This is essentially **compile-time prompt engineering** — the rules are pre-computed, not generated on the fly.

---

### 5. Master + Overrides Pattern (Persistence)

```
design-system/
├── MASTER.md           # Global Source of Truth
└── pages/
    └── dashboard.md    # Deviations ONLY
```

**Logic**: When building a page, check `pages/[page].md` first. If exists, override. Otherwise, MASTER.md is authoritative.

**Why it matters**: This is the same pattern as CSS inheritance or Kubernetes ConfigMaps. Reduces duplication, maintains hierarchy.

**Athena Observation**: This could be applied to our protocol system — `MASTER_PROTOCOL.md` + `overrides/[context].md`.

---

## Key Takeaways

1. **CSV > Vector DB for small domains**: For ~100-500 rows of domain knowledge, BM25 over CSV is simpler and equally effective.

2. **Pre-compiled reasoning rules**: Store decision logic in data files, not prompts. The AI reads the rules, doesn't generate them.

3. **Anti-patterns are as valuable as patterns**: The `Anti_Patterns` column prevents common mistakes (e.g., "AI purple/pink gradients" for banking).

4. **Cross-IDE portability**: Skills stored in `.shared/` can be symlinked or copied to any AI assistant's config folder.

5. **Pre-delivery checklists**: Every design system output includes a verification checklist. This is the equivalent of our Protocol 270.

---

## Usage in Athena

```bash
# Generate design system for a project
/ui-ux-pro-max Build a landing page for my SaaS product

# Direct script invocation
python3 .shared/ui-ux-pro-max/scripts/search.py "beauty spa wellness" --design-system -p "Serenity Spa"

# Persist for hierarchical retrieval
python3 .shared/ui-ux-pro-max/scripts/search.py "SaaS dashboard" --design-system --persist -p "MyApp" --page "dashboard"
```

---

## Tagging

# case-study #architecture #frontend #design-system #bm25 #ai-skills
