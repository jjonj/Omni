# Case Study: n8n Auto-Blog Workflow

> **Captured**: 18 December 2025  
> **Source**: Hadidiz YouTube tutorial  
> **Status**: 🔧 Implementable

---

## 1. The Concept

**Purpose**: Automatically generate SEO blog posts from YouTube transcripts.

**Stack**: Google Sheets → n8n → OpenAI → templated.io → Webflow CMS

---

## 2. Workflow Architecture

```
┌─────────────────┐
│  YouTube Video  │
│  (Transcript)   │
└────────┬────────┘
         │ Copy transcript
         ▼
┌─────────────────┐
│  Google Sheet   │
│  (Pending rows) │
└────────┬────────┘
         │ n8n trigger
         ▼
┌─────────────────┐
│   OpenAI API    │
│ Generate:       │
│ - Title         │
│ - Slug          │
│ - Summary       │
│ - Body          │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  templated.io   │
│ Generate image  │
│ (title overlay) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   Webflow CMS   │
│ Create draft    │
│ (manual review) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Google Sheet   │
│ Status: "done"  │
└─────────────────┘
```

---

## 3. Key Components

| Component | Purpose | Notes |
|-----------|---------|-------|
| **Google Sheets** | Input queue + status tracking | "Pending" → "Done" |
| **n8n** | Orchestration | Free, self-hosted |
| **OpenAI** | Content generation | Structured JSON output |
| **templated.io** | Dynamic image generation | Canva template + API |
| **Webflow CMS** | Publishing | Draft mode for review |

---

## 4. Implementation Notes

### Google Sheet Structure

| Column | Content |
|--------|---------|
| A | YouTube URL |
| B | Transcript (pasted) |
| C | Status (pending/done) |
| D | Blog ID (from Webflow) |

### AI Prompt Structure

- System prompt with example output
- Structured JSON output enabled
- Fields: title, slug, description, body

### templated.io Setup

1. Create Canva template (blank with text placeholder)
2. Export to templated.io
3. Use API to inject title text
4. Returns image URL

---

## 5. Advantages

- **Free workflow** (no paywall, shared in description)
- **Draft-only mode** (manual review before publish)
- **Scalable** (batch process many videos)
- **SEO-optimized** (structured for search)

---

## 6. Adaptation for Athena

If implementing for personal use:

1. Replace Webflow with GitHub Pages / Jekyll
2. Replace templated.io with Claude image generation
3. Add to `.agent/workflows/auto-blog.md`

---

## 7. When to Activate

- If building content-heavy site
- If need SEO traffic engine
- If repurposing video content to blog

---

## Tagging

# case-study #n8n #automation #seo #content #workflow
