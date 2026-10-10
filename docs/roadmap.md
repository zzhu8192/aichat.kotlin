# Roadmap

## Considered and rejected

### Weak/strong model tiers, and a `use_llm` tool the model routes to

Proposed as: label models weak and strong, give the model a context-free `use_llm(prompt)`
tool, and let it send the easy work to a local Ollama model. **Aliases were built from this and
the tiers were not**, deliberately, so that the idea is not re-derived from the alias map.

Three reasons, in order of weight:

1. **The model cannot make the call.** Deciding a subtask is "easy enough for the weak one"
   requires predicting a second model's competence, about which it knows nothing. Routing
   research (FrugalGPT, RouteLLM) routes *before* invoking the strong model, with a cheap
   classifier on the query -- not from inside it.
2. **The saving is small on the model this is built against.** From `pricing.yaml` at
   `dbu_rate: 0.07`, `qwen35-122b` is $0.22/M input and $2.20/M output, so a full 261k-token
   turn costs about 5.7 cents. A `use_llm` tool never removes a strong turn -- the strong model
   still runs the loop and reads the result -- so all it saves is bulk kept out of context,
   times the input rate, times the turns remaining. Keeping a 50k-token file out of context
   over 20 turns is 22 cents. On `kimi-k3` ($3.00/$15.00) the same saving is $3.00, so the
   value swings ~13x on which model is driving.
3. **Its failure mode is silent, at exactly the use case that justifies it.** The best case is
   large input, small output -- summarise this file. That is precisely where Ollama's own
   context window silently truncates a long prompt (no error, no `finish_reason`, nothing in
   `usage` -- kotllm already warns about it at startup), and the strong model then consumes a
   confident summary of the first 8k tokens as fact. Unlike `groovy_eval`, wrong output does
   not throw.

What survives, if this is picked up again: route *internal* jobs, where nothing has to judge
anything. RAG reranking first -- high volume, large input, small output, and a bad rerank is
discardable. Compaction (`Runner.summarizeForCompaction`) is architecturally the perfect fit
and the highest-stakes output in the app, so not first. If a model-facing tool is ever wanted,
give it a task-shaped name (`summarize(source, question)`) so the model judges *task shape*,
which it can see, rather than relative model strength, which it cannot. Note also that a hosted
flash model (`deepseek-v4-flash`, $0.14/$0.28) is cheaper per quality than local Ollama, with a
real context window and no minute-long local generation.

## Proposed, not yet approved

`.retry` with endpoint failover; a per-session cost (not just token) ledger.

**A real agentic stopping rule.** `max_tool_iterations` counts round trips, which is a poor
proxy for either progress or cost. Better, in rough order of value: stop on a token or
wall-clock budget rather than a count; detect non-progress (the same tool call with the same
arguments repeated) and stop with that as the reason; and a `.continue` command that resumes an
interrupted loop without the user having to phrase it as a message. None of this is built.

Built without explicit sign-off, flag if unwanted: `--dry-run`, which prints the fully
assembled request JSON instead of sending it. It was needed to verify the wire format and will
be needed again to debug `<% %>` template expansion. Its REPL twin `.dump` is *not* built.
