# kotllm design docs

[README.md](../README.md) is the user manual -- every command, flag and config key.
These files are the design record: what each subsystem does, and why it is the way it is.

| Doc | Covers |
| --- | --- |
| [architecture.md](architecture.md) | Module layout, dependency policy, config format |
| [building.md](building.md) | Maven, versioning, the stale-jar trap |
| [clients.md](clients.md) | Wire format, reasoning parts, `/think`, Azure Foundry, Ollama |
| [repl.md](repl.md) | Framed chrome, the pinned region, command surface, Ctrl-C, `--list-*` |
| [tools.md](tools.md) | Tool harness, approval, file tools, the task checklist, user tools |
| [engines.md](engines.md) | Groovy / Java / Python interpreters and the data-plane tools |
| [rag.md](rag.md) | Chunking, BM25, the local n-gram encoder, fusion, injection |
| [memory.md](memory.md) | Markdown memories, tiering, the named upsert |
| [agents.md](agents.md) · [agent-design.md](agent-design.md) | Agents as templates: five calls, no format |
| [skills.md](skills.md) · [skills-design.md](skills-design.md) | `SKILL.md`, the portable six, three tiers |
| [templating.md](templating.md) | `<% %>` and `{{ }}` |
| [input.md](input.md) | `-f`/`/file`, images, HTML to markdown, keyless web search |
| [sessions.md](sessions.md) | JSONL persistence, chat search, compaction, background turns |
| [cost.md](cost.md) | Pricing, the metrics ledger, client-side rate limiting |
| [rendering.md](rendering.md) | Terminal encoding, glyph fallbacks, LaTeX |
| [testing.md](testing.md) | How to test this, and two traps that pass CI and fail locally |
| [code-flow.md](code-flow.md) | End-to-end call flow |
| [spark-connect-design.md](spark-connect-design.md) | Why the dependency was `provided` in Scala; not yet carried into this pom |
| [history.md](history.md) | **Bugs that shipped, and the measurements behind the fixes** |
| [roadmap.md](roadmap.md) | Proposed, not yet approved |
