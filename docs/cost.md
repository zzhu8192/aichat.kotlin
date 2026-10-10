# Cost, metrics and throttling

## Session cost

An estimate of what the session has spent, split input from output. Dollars in the footer,
dollars *and* DBUs in `/status`, one line in `/info`.

```
cost in               4,886 tok     0.0154 DBU   $0.0011
cost out                 34 tok     0.0011 DBU   <$0.0001
cost total            4,920 tok     0.0164 DBU   $0.0011
                 estimate: list price, $0.07 per DBU, table verified 2026-09-18
                 cached input is not reported by the endpoint, so input cost is an upper bound
```

**It is an estimate, and the biggest reason is not arithmetic.** The gateway's `usage` block
reports `prompt_tokens` and `completion_tokens` and says nothing about cache hits, so a prompt
served from cache is charged at full rate. Every figure is therefore an **upper bound** on input
cost -- the right direction for a number someone uses to decide whether to keep going, and the
caveat is printed next to the total rather than buried here.

**Databricks publishes DBU per million tokens, not dollars**, so `pricing.yaml` stores DBU and
`dbu_rate` (default `0.07`, the list rate for Mosaic AI model serving in a base region) converts.
A workspace on a negotiated rate changes one number instead of a table. Azure entries are already
in USD and ignore the rate.

**The footer shows only the dollar figure.** The DBU derivation needs three columns to be
worth anything, and the footer is a line; `/status` has the room to show the arithmetic. The
element carries `DropCost`, between tokens and requests, so a narrow terminal sheds the `/help`
hint and the version before it -- and it is **omitted entirely when nothing priced has been
spent**, so an unpriced model never displays a misleading `$0.0000`.

### An unpriced model is named, never folded in

A total that silently excludes half the session is worse than no total, so a model matching
nothing is reported by name with its token count, kept out of the priced total, and shown in the
footer as amber `unpriced`. The fix -- add a key -- needs the name to be visible, which a count
could not provide.

`pricing_enabled: false` reports everything as unpriced rather than as free, for the same reason.

### Per turn, at that turn's own model

`Pricing.breakdown` folds over `ledger.all` and prices each turn with `TurnMetrics.model`. That
field already existed, which is the only reason this was cheap -- a session that used `/model`
halfway through would otherwise be costed entirely at whichever model happened to be selected
when the report was asked for. `Runner.cost` recomputes rather than accumulating, so a `/set` or
a config reload cannot leave a total that is part old prices and part new.

### Where the table lives

`src/main/resources/pricing.yaml`, bundled so it works with no setup, extended or overridden by a
`pricing:` block in `config.yaml` of the same shape. A user key that exactly matches a bundled one
**replaces** it rather than sitting beside it, since equal-specificity keys would make the choice
arbitrary.

Honest note on coverage: **the Databricks half is authoritative and complete**, taken from
Databricks' own Foundation Model Serving page, which publishes a full DBU table. **The Azure half
is thin, and not for lack of trying** -- both the Foundry Models and Azure OpenAI pricing pages
render every price as `$-` and direct you to a calculator or to sales, and the current lineup
(GPT-5.4/5.5/5.6, with GPT-6 "in processing") has no published per-token table to copy. The
entries there are the widely attested ones; anything actually run on Azure should be checked
against your own calculator and added.

## Metrics and `/status`

`/status` is the Claude-Code-style session report: token usage over time plus the metrics that
say whether a session is behaving. Named with a dot for consistency with every other REPL
command, not `/status`.

All accounting lives in `util/TokenLedger`, which keeps **one `TurnMetrics` per turn** rather
than running totals. That is the whole point of the feature: a cumulative counter cannot show
that prompt tokens are climbing toward the context wall, and the sparkline can. `Session`
delegates `lastUsage` and `billed` to the ledger so there is a single source of truth.

Things the report deliberately gets right:

- **Context pressure is the next prompt, not the session total.** Every turn re-sends the whole
  history, so cumulative tokens pass the window size long before the window is actually full.
  Reporting the total against the window would cry wolf constantly. It is `Runner.contextSize`,
  not the ledger's last row: the row is what the endpoint charged, and `/empty`, `/compact` or
  a memory edit changes what the next request carries without any row being written. Marked
  `(projected)` when the endpoint has not counted that exact payload.
- **Reasoning volume is reported separately**, in characters. The endpoint's `usage` has no
  reasoning breakdown, and on this model reasoning dominates: two short questions produced 86
  prompt tokens and 10,339 characters of reasoning. Without this line the token counts look
  inexplicable.
- **Time-to-first-token is measured from the first *content* event.** This endpoint attaches a
  cumulative `usage` to every chunk including the opening role-only one, so timing the first
  event of any kind would always report ~0.
- **Throughput excludes the first-token wait**, so it measures generation speed rather than
  queueing.
- **`/empty` does not reset the ledger.** Clearing the conversation changes what gets sent; it
  does not un-spend the tokens. The prompt series dropping after a clear is useful signal.
- Turns with no usage (an aborted stream) record zeros rather than a guess.

## Throttling

`rate_limit` applies client-side rate limiting over a rolling window, so hitting a provider
limit becomes a visible pause rather than a 429 that wasted a round trip -- and, on a long
conversation, re-sent a large prompt to discover it. Configurable globally or per client;
limits belong to an endpoint or workspace, so the per-client override is the usual case.

The asymmetry between input and output is inherent, not a shortcut:

- **Input is checked before sending, from an estimate.** True `prompt_tokens` only arrives with
  the response, so `util/TokenEstimator` guesses, the request is charged at that estimate, and
  the window is corrected to the real number on settle. Without the correction the window would
  drift by the estimator error, compounding across a session.
- **The estimator calibrates itself** against observed `prompt_tokens` instead of hard-coding
  chars-divided-by-four, which is wrong by a wide margin for code, JSON or CJK. It converged to
  3.12 chars/token on Qwen 3.5-122b. Implausible observations (ratio outside 0.5--20) are
  discarded rather than allowed to poison it.
- **Output can only be counted afterwards.** An `output_tokens_per_minute` or
  `total_tokens_per_minute` limit therefore throttles the *next* request, never the current one.

`RateLimiter` uses reserve-then-settle with an injected clock, so the tests advance time instead
of sleeping. Other decisions worth keeping:

- A request whose estimate exceeds the entire budget returns `Oversized`: waiting cannot help,
  so it is sent anyway and the provider rules on it. Blocking forever would be the wrong call.
  This is reachable in normal use -- a growing conversation against a short window hits it.
- A request the provider *rejected* is settled, not released: it counted against their limit.
  A request that never reached them (an `IOException`) is released, so a connection failure does
  not eat budget.
- `Retry-After` from the provider overrides our backoff curve, capped at 60s so a long
  instruction cannot silently hang the REPL.

**Local throttling matters less than it looks for interactive use.** A turn on this endpoint
takes roughly 8 seconds, so against a short window earlier turns age out before a later one is
submitted. Three identical turns against a 100-token/10s budget never throttled when they took
65s, and throttled once for 5.4s when they took 21s. It earns its keep on large windows with
large prompts, or once background turns run concurrently.

