# OmniDesign — approved design and v1 native document contract

- **Visual/interaction source (approved on MISC-0160 + MISC-0161, R3/A):** [OmniDesign-r3-prototype.html](OmniDesign-r3-prototype.html) and [design handoff](MISC-0161-design-handoff.md).
- **Architecture, offline sync and agent API:** [MISC-0162-architecture.md](MISC-0162-architecture.md).
- **Versioned editable document contract:** [omnidesign.document.v1.schema.json](omnidesign.document.v1.schema.json).
- **Editable native sample:** [example.architecture.omd.json](example.architecture.omd.json).

**Web editor implemented (MISC-0163/MISC-0164):** [shared React/TypeScript editor](../../OmniDesign/web/README.md), native JSON roundtrip, IndexedDB autosave, SVG/PNG export, stencil packs and adaptive controls. **OmniHub service (MISC-0165):** [FastAPI/SQLite backend and API](../../OmniDesign/service/README.md) registered privately as Gateway tool `omnidesign` (on demand; direct loopback health verified). The editor now has explicit Hub save/library actions. Android shell (MISC-0168), agent integration/diff UI (MISC-0166), and remote/device acceptance remain subsequent work. Only R3 visual direction was approved; R1/R2 are historical.
