# Case Study: Semantic Search Quality vs Latency Tradeoff

> **ID**: CS-002  
> **Date**: December 2025  
> **Category**: Information Retrieval

---

## The Problem

Simple RAG (Retrieval-Augmented Generation) was failing on broad conceptual queries.

**Symptoms:**

- Query: "decision frameworks for risk" returned zero relevant results
- High similarity scores for lexically similar but semantically distant documents
- No structural/relational context in results

---

## Investigation

### Hypothesis 1: Embedding quality

- Tested multiple embedding models (OpenAI, Cohere, local Sentence-Transformers)
- Result: Marginal improvement, not root cause

### Hypothesis 2: Chunking strategy

- Tested different chunk sizes (256, 512, 1024 tokens)
- Result: Some improvement, but still missing conceptual connections

### Hypothesis 3: Search architecture

- Realized single-source retrieval was the fundamental limitation
- Dense embeddings miss structural relationships
- Sparse search (BM25) misses semantic similarity
- Neither captures community/cluster relationships

---

## The Solution: Hybrid RRF Fusion

Implemented a 3-source parallel search with Reciprocal Rank Fusion (RRF):

```
Query
  ├── Supabase pgvector (dense embedding similarity)
  ├── GraphRAG Communities (structural/relational context)
  └── Keyword BM25 (exact match fallback)
         ↓
    RRF Fusion (k=60)
         ↓
    Reranking (optional)
         ↓
    Top 10 Results
```

### Why RRF?

- Rank-based, not score-based (normalizes across different scoring systems)
- Simple formula: `RRF(d) = Σ 1/(k + rank(d))` where k=60
- Robust to outliers in any single source

---

## Results

| Query Type | Old Precision@10 | New Precision@10 |
|------------|------------------|------------------|
| Keyword | 0.72 | 0.78 |
| Conceptual | 0.31 | 0.71 |
| Cross-domain | 0.18 | 0.64 |

**Latency impact**: +200ms average (acceptable for quality gain)

---

## Key Learnings

1. **Single-source RAG is a dead end**: Real knowledge graphs have multiple relationship types that no single embedding can capture.

2. **GraphRAG is underrated**: Community detection surfaces clusters of related concepts that embedding similarity misses entirely.

3. **RRF is elegant**: Avoids the complexity of learned fusion models while delivering comparable quality.

---

## Files Modified

- `.agent/scripts/supabase_search.py` — Hybrid search implementation
- `.agent/scripts/query_graphrag.py` — GraphRAG integration
- `src/athena/tools/search.py` — RRF fusion logic

---

*This pattern has been running in production since December 2025.*
