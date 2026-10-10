# Clients and the wire format

Databricks AI Gateway, Azure AI Foundry, and any OpenAI-compatible server
including Ollama. All three go through one codec.

## Wire-format findings (verified against the live endpoint, 2026-09-15)

Tested `POST https://adb-7193300288064453.13.azuredatabricks.net/serving-endpoints/chat/completions`
against `databricks-qwen35-122b-a10b`. **These are not theoretical - the client must handle them.**

1. **Routing.** The gateway accepts the collection route `/serving-endpoints/chat/completions`
   with `model` in the request body. No per-endpoint `/invocations` URL rewrite is needed, which
   is why the `patch:` block was safely commented out in the reference config.

2. **`content` is an array of typed parts, not a string.** This is an OpenAI
   chat-completions *envelope* wrapping Responses-API-style content:

   ```json
   "content": [
     {"type":"reasoning","summary":[{"type":"summary_text","text":"Thinking Process:..."}]},
     {"type":"text","text":"4"}
   ]
   ```

   Streaming carries the same shape: each SSE chunk's `delta.content` is an *array of parts*,
   not a string. Any stock OpenAI decoder - including the `openai-compatible` client in aichat -
   will throw or silently render nothing. **`ContentCodec` must accept `String | Array[Part]`
   on both the non-stream and delta paths** and route `reasoning` parts to a channel separate
   from `text` parts.

3. **Reasoning is verbose, unpredictable, and eats the output budget.** "What is 2+2? Answer
   briefly." spent 279 completion tokens, 278 of them reasoning. At `max_tokens: 16` the
   response hit `finish_reason: "length"` *inside* the reasoning block and returned no visible
   text at all. Worse, the length is not proportional to task difficulty: **"Count from 1 to 3,
   digits only." consumed a full 2048-token budget and still emitted zero text parts.**
   Therefore:
   - **Do not default `max_tokens` at all.** Any cap we pick is a truncated-reply bug waiting to
     happen; the endpoint's own default is large and is the safer failure mode. `Config.maxTokens`
     defaults to `null` for this reason, and tests that expect an answer must not set it.
   - Detect and explain the degenerate reply (empty text + non-empty reasoning) rather than
     printing nothing -- `Runner.warnIfDegenerate`.
   - A `reasoning: show | collapse | hide` display setting, defaulting to `collapse`. That is
     **display only**; `reasoning_effort` / `/think` is the request-side control -- see
     "`/think`" below.
   - **Never replay reasoning parts back into request history** (pure token burn, and endpoints
     may reject unknown part types on input). `Session.appendResponse` strips them.

4. **`usage` is present on every stream chunk**, cumulative. Token and percent-of-context
   accounting is free - no local tokenizer estimate needed. **This is a Databricks courtesy,
   not the OpenAI contract**: everyone else sends no `usage` on a stream at all unless the
   request carries `stream_options: {"include_usage": true}`. `DatabricksClient` sets it for
   the Ollama and generic-OpenAI providers and not for Databricks, where it is redundant and
   an unknown body key on the live-tested path is a risk with nothing to buy. Omitting it is
   what made the token counter sit at zero for a whole Ollama session - see docs/history.md.

5. **Tool calls use the standard OpenAI shape** (`tool_calls[].function.arguments` as a JSON
   *string*, `finish_reason: "tool_calls"`), arriving alongside a `reasoning` part in the same
   message. Verified working with a real tool definition.

---

### `/think`: asking for less thinking, which is not the same as hiding it

Two settings, one letter apart in the config file and completely different in effect:

| | What it is | What it changes |
| --- | --- | --- |
| `reasoning: show \| collapse \| hide` | a **renderer** setting | what reaches the screen |
| `reasoning_effort: default \| none \| minimal \| low \| medium \| high` | a **request** parameter | whether the tokens are generated at all |

**`reasoning: hide` costs nothing and saves nothing.** The model still thinks, the tokens are
still billed, and they still consume the output budget -- finding 3 above has "Count from 1 to 3,
digits only" spending a full 2048-token budget inside the reasoning block and emitting no visible
text, which hiding the display would not have improved by one token. `/info` therefore labels the
display key `(display only)`, because the confusion is the expensive kind.

**One parameter for all three providers**, which is only possible because they converged on
OpenAI's spelling: Azure/OpenAI defines `reasoning_effort`, Ollama passes it through on `/v1`, and
a Databricks-served model either honours it or ignores it. Anthropic's
`thinking: {budget_tokens}` shape is deliberately **not** modelled -- a token budget is a
different kind of control, and supporting both would make one setting mean two things depending
on the endpoint.

Decisions worth keeping:

- **Absent is not `none`.** `default` sends no field at all and lets the provider decide; `none`
  asks it not to think. Collapsing those would make adding this key change what every existing
  config puts on the wire, and there is a test asserting it does not.
- **`off` maps to `none`.** That is what someone types when they mean "stop thinking", and the
  parser also takes `no`, `false`, `disable`, `disabled`, plus `provider`/`unset` for default.
- **An unknown level is passed through, with a warning, rather than refused.** The levels are
  provider-defined and new ones keep appearing (`minimal` arrived with GPT-5), so rejecting one
  kotllm has not heard of would age worse than letting the endpoint rule on it. `/think` says it
  is unrecognised and sends it anyway.
- **`/think` is its own command, not only `/set reasoning_effort`.** This is the knob people reach
  for mid-session when a model is burning its budget thinking, and `/set` is where settings go to
  be forgotten. Both work; the completer offers the levels. Named `/think` rather than
  `/reasoning`, which would read as the sibling display key.
- **Support is stated up front, never inferred.** `kotllm cannot tell whether the setting was
  honoured`: a model that ignores the field and thinks anyway is byte-for-byte indistinguishable
  from one that honoured it and decided to think. So `/think` prints what is known about the
  current provider and points at the one real measurement -- `/status` reports reasoning volume in
  characters, which is what actually shows whether anything changed.

Verified under a real pty (piping stdin will not do: a `/` in an empty buffer triggers the
completion widget, as the chrome notes explain): `/think` reports `default`, `/think low` sets it,
`/think` again still reports `low`. And on the wire, through `--dry-run`: no `reasoning_effort`
key by default, `low` when configured, `none` for `off`.

## Ollama, and why there is no Ollama client

Local models via Ollama, over its **OpenAI-compatible `/v1` surface**. There is no Ollama
transport, no second codec, and no `/api/chat`.

**The user asked the right question: why build anything, when Ollama already speaks OpenAI and
that is what this client is?** The honest answer was that almost nothing needed building. Going
through what a native client would have bought:

| Claimed benefit of `/api/chat` | Verdict |
| --- | --- |
| `/api/tags` model discovery | **Withdrawn.** `/v1/models` is standard and Ollama implements it. |
| the `thinking` field for the reasoning channel | **Withdrawn.** `/v1` carries it as a sibling field, and the decoder already handled `reasoning_content`. |
| `keep_alive` | Marginal, and a server-side concern. |
| `/api/show` context length | Native-only, and it saves writing one config field once. |
| `num_ctx` | The only one with teeth -- see below. |

One marginal benefit, for a second transport with newline-delimited-JSON framing. And the
generalisation cuts the other way: work on the OpenAI path serves **vLLM, llama.cpp, LM Studio,
LocalAI and OpenRouter** too, while work on `/api/chat` serves exactly one server. So
`Provider.Ollama` exists to change three things about an otherwise generic client, and
`Client.forTarget` hands it `DatabricksClient` like any other OpenAI endpoint.

Worth recording that the case *for* a native client was overstated before it was checked, and one
claim -- that discovery needed `/api/tags` -- was simply wrong.

### The four papercuts, which is what actually needed fixing

- **`type: ollama` was a hard error.** It fell to `Provider.Unknown` and `Client.forTarget`
  refused, so the user had to know to write `openai-compatible` -- for a server whose entire
  appeal is needing no setup. Port **11434** also infers it on any host, following the precedent
  that an explicit azure/databricks kind wins but a URL beats the generic label.
- **`/v1` is appended to a bare host.** Ollama's own documentation shows `http://localhost:11434`,
  so that is what gets pasted, and the POST then goes to `/chat/completions` at the root and 404s
  with nothing explaining why. `api_base` is optional entirely, defaulting to
  `http://localhost:11434/v1`. An explicit version segment (`/v1`, `/v1beta`) is untouched.
- **A colon is part of an Ollama model name, and `client:model` was eating it.** `qwen3:27b`
  parsed as client `qwen3`, model `27b`, and failed with "unknown client 'qwen3'" -- the most
  ordinary thing a local user can type. The qualified form is now recognised **only when the
  prefix really names a configured client**, which is exactly the rule `Commands.parse` already
  uses for a leading slash. An undeclared name also passes through *on an Ollama client only*,
  since `ollama pull` should not need a config edit; for a configured gateway the model list is
  fixed, so a name outside it is a typo and the local "unknown model" error is far better than an
  opaque 404. Two existing tests encoded that and were right to.
- **A local model is free, not `unpriced`.** `unpriced` means "I could not find a price", which
  warns that the total is understated by an unknown amount; local means "this cost nothing", which
  is a fact needing no remedy, and reporting it in amber would train the user to ignore the one
  signal that matters. `/status` says `local, free` in green, and the footer shows **nothing** --
  `$0.0000` would waste the one slot the cost element gets. Recorded per turn on `TurnMetrics`,
  for the same reason the model name is: `/model` can switch from local to hosted mid-session.
  Derived from the *provider*, never the model name, because a local model can be called `gpt-4o`
  and a price-table hit would invent a bill for the user's own GPU.

### `num_ctx` is deliberately not sent

**Ollama runs every model at its own configured window regardless of what the model supports, and
truncates a longer prompt silently** -- no error, no `finish_reason`, nothing in `usage`. kotllm
is more exposed to that than most clients, because compaction, the context gauge and
`read_to_var` are all driven by `max_input_tokens`.

It is still not kotllm's to set. `num_ctx` allocates GPU memory, and a chat client quietly
changing a server's memory footprint per request is the wrong layer -- it is also settable only on
the native API, which is the one remaining argument for building one. So the hazard is **named
once at startup** instead, whenever an Ollama model has no `max_input_tokens`, pointing at
`OLLAMA_CONTEXT_LENGTH` or a Modelfile `num_ctx`. The user applies it once and every client of
that server benefits. `Runner.startupHints` carries it alongside the tool hint, printed in both
the REPL and single-hit mode rather than pinned, because both are prose about a config file.

### Two improvements that are generic on purpose

- **`client/Models.kt`: `GET /v1/models`.** Written against the standard endpoint, so
  `/model discover` and `--list-models --discover` work for every OpenAI-compatible server. Never
  called unless asked -- discovery on startup would put a round trip in front of `--version`, and
  a hang in front of the prompt when a local server is down -- and an unreachable server is a
  reported reason, never an empty list that reads as "you have no models". No capability guessing:
  the endpoint returns ids and nothing else, so a discovered model gets `ModelConfig.synthetic`
  and anything declared in config wins. Inventing a context window here would feed a wrong number
  straight into compaction.
- **Three spellings of the reasoning channel.** `reasoning_content` was already handled on both
  the blocking and streaming paths; Ollama uses `thinking`, and some servers a bare `reasoning`.
  All three now decode to `Part.Reasoning`, from one list shared by `Message.decode` and
  `Chunk.decode`. The cost of missing one is a model's chain-of-thought rendered as its answer.

Verified end to end against a stub on port 11434, since this machine has no Ollama: no `api_base`
at all resolved to the default and answered; `qwen3:27b` resolved as a model name; `thinking`
landed in the reasoning channel; no `Authorization` header was sent (the stub asserts it);
`--list-models --discover` returned both installed models; and the context hint fired with
`max_input_tokens` unset and went silent once set.

