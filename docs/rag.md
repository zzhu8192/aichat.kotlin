# Retrieval (RAG)

Hybrid lexical + character n-gram retrieval over a local folder, exposed to the
model as the `search` tool. No service, no embedding model, no new dependency.

## RAG

Hybrid retrieval over a local folder, exposed to the model as a tool. **Phase 1 (BM25 and the
`search` tool) is built**; dense vectors are Phase 2. The design notes below are kept because
they record what was verified and why the shape is what it is.

### Chunking

Sizes are configured in **tokens**, matching the guidance and aichat, but converted internally
through a chars-per-token ratio, because kotllm has no tokenizer -- only the calibrated estimate,
and that is calibrated on request JSON rather than prose. **Chunk sizes are therefore approximate,
by roughly 15%.** Bundling a tokenizer would mean per-model vocabulary files, which is not worth
it for a boundary decision.

Profiles, selected by `rag_profile` (`auto` picks per file by extension):

| Profile | Size | Overlap | Rationale |
| --- | --- | --- | --- |
| `faq` | 128-256 | 20-40 | Pinpoint facts; minimises semantic dilution |
| `docs` (default) | 256-512 | 50-100 | One or two complete paragraphs |
| `longform` | 512-1024 | 100-150 | Legal and academic text needs surrounding definitions |
| `code` | structure-aware | by block | Blind slicing breaks syntax and scoping |

Prose uses recursive separators, paragraph then line then sentence then word, **with a hard
character fallback**: minified JSON or a 40,000-character single line defeats every separator, and
splitting must still terminate.

### BM25 and fusion

Standard BM25 over an inverted index, with `k1` and `b` configurable (1.2 / 0.75). RRF merges the
ranked lists as the sum over retrievers of `1/(k + rank)`, `k` default 60. **RRF is fusion, not
reranking** -- it needs no score normalisation, which is exactly why it suits this case, since
BM25 scores and cosine similarities are not comparable. A cross-encoder reranker is a separate
optional stage; `rag_reranker_model` is reserved for it.

### Indexing rules

- Include source and text by extension; exclude known binaries (`pdf`, `docx`, `class`, `jar`,
  `zip`, archives, executables). Default to ignoring pdf and docx, but honour `document_loaders`
  when one is configured, since that conversion already exists.
- **Incremental by size and mtime**, recorded per file in the metastore. Cheap, and the failure
  mode -- an edit preserving both -- is rare enough to document rather than pay a content hash
  for on every file.
- **Deletions and renames must be handled.** The requirements as written cover new and edited
  files only; without removal a deleted file keeps answering queries, and a stale index that lies
  is worse than no index. A rename is a delete plus an add.
- **Model and dimension are recorded, and a mismatch refuses to query.** Changing the embedding
  model leaves stored vectors numerically comparable but semantically meaningless, which is
  silent garbage -- the worst failure mode available.
- **Chunks carry path and line range**, so the model can cite and the user can verify.
- Vectors persist as a **binary float32 sidecar**, not JSON: 10k x 1024 floats as JSON text is
  enormous and slow to parse.

### Phase 1, as built

BM25 retrieval, no embeddings, no new dependencies. `/rag index <folder>` / `/rag` /
`/rag use|off|drop|reindex|search|describe|inject`, `--rag <name>`, `--rag-index <folder>`,
`--rag-describe <text>`, and the `search` tool.

**`--rag-index` exits when there is nothing else to do** rather than falling through to the
REPL, which is the whole reason it exists: an index could previously only be created
interactively, so scripted setup was impossible, and a setup flag that opens a prompt hangs the
script it was added for. With TEXT it indexes and then answers. `Main.dispatch` holds that rule,
and `Main.applyRagFlags` runs `--rag-index` before `--rag` so the latter can still redirect
retrieval after the former has activated what it built.

Note for unattended use: approval defaults to `confirm` and there is no terminal in a script, so
a `search` call is refused unless `tool_approval: auto` or an allowlist entry is set. That is the
documented approval policy working as intended, not a retrieval bug -- but it does mean
`--rag-index . "question"` needs one of the two to be useful. See the proposal below.

Things that are load-bearing:

- **The tool is named `search`, and `Tools.SearchName` is the single source of that string.**
  It is the contract the model learns; it must not change when vectors land. The injected
  context block interpolates the same constant rather than repeating the literal, so a rename
  cannot leave the prompt telling the model to call a tool that no longer exists.
- **`search` is registered whenever RAG is enabled, but only *offered* while an index is
  active.** `Runner.activeTools` filters it per request. A tool the model can only fail to use
  still costs schema tokens on every request and invites a pointless call; recomputing per
  request means `/rag index` makes it available immediately and `/rag off` withdraws it.
- **No single file may monopolise the head of the results** (`rag_max_per_file`, 3). BM25 ranks
  chunks, not files. Found by live-indexing this repository: searching `Compactor.kt` returned
  five consecutive chunks of `CompactorTest.kt`, pushing `Compactor.kt` to rank six and out
  of a top-five window. The cap is on *position*, not membership -- held-back passages are
  appended in score order, so it can never return fewer results than the uncapped version, and a
  question whose answer really is concentrated in one file still gets that file's other chunks.
  Unit tests alone would not have found this; the flooding needs a real corpus with real file
  size distribution.
- **Results are capped in total size and say how many passages were withheld**, dropping whole
  passages from the tail rather than truncating one. A passage cut mid-sentence reads as if the
  file ended there. One tool call must not be able to spend the whole window.
- **The path is indexed alongside the chunk text** (`s"${c.path} ${c.text}"`), so `AuthController`
  finds `AuthController.java` when the body never names it. Path-filtered searches therefore
  over-fetch before filtering, or the filter would silently shrink the result set.
- **A basename collision is refused, not merged.** Two folders both called `shared` would
  otherwise interleave unrelated corpora into one index, making every later answer subtly wrong
  with nothing on screen to explain it.
- **The active index is marked on disk**, so it survives a restart; a marker naming an index
  that no longer exists is forgotten rather than failing every later call.
- **Chunk sizes have a 200-character floor.** `maxChars = max(200, tokens * ratio)`. A test that
  assumed a tiny configured size produced tiny chunks was wrong, not the code.
- **The BM25 stoplist keeps `if`, `for`, `case` and `not`.** In a codebase they are content, and
  IDF already de-weights a term that appears everywhere, so a hand-written stoplist mostly does
  damage. A comment once claimed this while the list contradicted it; the list was fixed.

**A tool schema makes `search` available; it does not make it discoverable.** The schema says
the tool exists, not that a corpus of the user's own material is one call away -- so a question
that could plausibly be answered from general knowledge was answered that way, and the index sat
unused. `RagStore.systemPromptSection` adds a standing notice while an index is active, on the
same reasoning as the memory section: the model can only weigh what it has been told about.
Verified live -- "How does BM25 length normalisation work?", a textbook question, now retrieves
and answers from the user's own `Bm25.kt` with a line citation instead of from training data.

**The notice carries a user-written description, never the folder path.** The user's call, and
right: an absolute path is noise the model cannot act on, citations are relative to the index
root anyway, and it would put the local filesystem layout into every request and every tool
result for nothing. `/rag describe <text>` / `--rag-describe` set it; `describeActive` falls back
to the index name. There is a test asserting the root never appears in tool output.

The description **persists** in `IndexMeta.description`, which is not a relapse into the
`autoInject` mistake: it labels the material rather than switching anything on, so re-typing it
every session would be absurd, whereas injection spends context unconditionally and therefore
has to be a per-session act. It is written through `RagIndex.saveMeta`, a metadata-only path, so
setting one string does not re-encode every chunk and rewrite a multi-megabyte sidecar.

Injection (`/rag inject on`) is **session state, off by default, and cannot be made global.**

The first version persisted it per index in `IndexMeta.autoInject`. The user rejected that:
injection must be for the session, and "you should never be able to set it globally". So the
flag was removed from the on-disk format altogether rather than merely defaulted off --
`RagStore.injectThisSession` is the only place it lives, there is no config key, and the
`auto_inject` key is now written by nothing and read by nothing. Leaving a persisted field in
place would have left a global switch reachable by hand-editing, which is the thing that was
ruled out.

It resets whenever the *active index* changes -- `indexFolder`, `activate`, `deactivate` -- but
not on `reindex`, since re-scanning the same folder is not a change of corpus. Silently
injecting from a newly activated folder would be precisely the unasked token spend the default
guards against.

- Built rather than left as a stored flag nothing reads. That was the one place kotllm actively
  over-promised before (`remember` wrote a file nothing loaded), and a switch that does nothing
  is the same bug.
- **Rebuilt per request, never written into history**, exactly like memories -- so editing a
  file takes effect on the next message and the conversation cannot accumulate stale copies.
- **Retrieval runs against the last message the *user* wrote**, not the tool results.
  Retrieving against tool output would let the injected context drift away from the question
  over a long loop.
- It is counted in `fixedOverheadTokens`, because compaction cannot touch it either. Leaving it
  out would have made the compaction futility check optimistic by however much was injected.
- `rag_inject_top_k` (4) is deliberately much smaller than `rag_top_k` (8): a block that rides
  on *every* request must be smaller than a result set the model asked for deliberately.

### What was shared, and what was not

`input/Loaders.kt` was extracted so the `document_loaders` conversion has exactly one
implementation, used by both `/file` input and RAG indexing. A format `/file` can read is one
RAG should be able to index, and two copies would drift.

**The extension and skip-directory lists were deliberately not shared.** `input/Input.kt`
maps extensions to *markdown fence languages* for prompt display; `rag/Rag.kt` needs an
allowlist geared to indexing plus a denylist that explains *why* a file was refused. Same-looking
data, different jobs.

**The skip-directory sets were unified once the third caller appeared**, which was `glob` and
`grep`. They now live in `util/Skips` along with the binary-extension list, and both old call
sites delegate. Worth knowing that the copies *had* already drifted by then -- Rag's directory
set was a strict superset of Input's -- so the predicted failure had happened silently before
anyone went looking. `FileToolsTest` asserts the two are the same object, not merely equal in
spirit.

### Phase plan

**Phase 1 is built.** Chunker, BM25 index, metastore with incremental scan and deletion
handling, the REPL surface, `--rag`, the `search` tool, and opt-in injection.

**Phase 2 is built, as a local encoder rather than a service.** See below.

**Phase 3, optional.** An aichat-style `__CONTEXT__` / `__INPUT__` template for the injected
block, and a reranker stage (`rag_reranker_model` is reserved, nothing reads it). Distributional
similarity via LSA over the corpus co-occurrence matrix is the one remaining way to get real
semantics without a dependency or a service -- randomized SVD is a few hundred lines, and the
projection would have to persist with the index.

### Phase 2, as built: a local encoder, no service

**The user ruled out an external embedding model.** Not Databricks, not their own endpoint,
not any provider: "build the code only generic no dependency embedding encoder." So Phase 2 is
`rag/Embedder.kt` and `rag/VectorIndex.kt`, and the `/embeddings` client method, batching,
rate-limit routing and cost estimation described above were **never built and are not needed**.
The endpoint facts are kept as a record of what was probed, not as a plan.

`HashingEmbedder`: character n-grams hashed into `rag_embedding_dims` buckets, sub-linear term
frequency, L2 normalised, cosine compared, fused with BM25 by RRF.

**Character n-grams, not hashed words.** A hashed bag of words is a worse reimplementation of
BM25, and fusing two rankers that agree by construction adds nothing -- the fusion would be
decorative. Character n-grams are chosen because they match what exact-token search
structurally cannot: morphology, typos, and identifier fragments. Verified on this repository:
`compaction` sends BM25 to `examples/config.example.yaml` and `README.md` and misses `Compactor.kt`
entirely, while the dense half ranks `Compactor.kt` first; the typo `declineToolCals` reaches
`Runner.kt` through the dense half and nothing usable through BM25.

**It is not semantic and must never be described as such.** No synonyms, no paraphrase --
`car` does not retrieve `automobile`. `HashingEmbedderTest` asserts that pair stays far apart
specifically so a later change to the tool description cannot quietly promise semantics the
retriever does not have.

**Stateless, deliberately.** No IDF table, no corpus statistics, no fitted state: `encode` is a
pure function of the text and the constructor parameters. IDF would measurably improve the dense
half in isolation, and was still rejected:

- Query and document vectors are consistent *by construction*. A persisted IDF table that
  drifted as files were added would encode queries in a different space than the documents, with
  nothing visibly wrong.
- Incremental indexing needs no invalidation rules.
- BM25 already is the IDF-weighted ranker. Leaving weighting out here is what keeps the two
  halves complementary rather than nearly duplicate.

The absence of IDF is compensated in `normalise`, which drops tokens under three characters and
a short list of function words before n-gramming. Without that, n-grams like `the`, ` th` and
`ing` consume a large share of a unit-length vector that should be spent on what distinguishes
one chunk from another.

#### The similarity floor, and the measurements behind it

**Cosine is essentially never zero between two real texts**, so the dense half returns its
nearest neighbours for *any* input, including gibberish -- and RRF then gives them confident
ranks. This broke two tests the moment vectors were switched on: `No passages matched` stopped
firing, which is an honest and useful signal, and a nonsense query started returning eight
irrelevant passages for the model to synthesise from.

`rag_min_similarity` (0.20) is the floor. Both it and `rag_embedding_dims` were set from a
sweep over this repository rather than guessed:

| dims | index | sidecar | wanted (min) | nonsense (max) | margin |
| --- | --- | --- | --- | --- | --- |
| 512 | 574ms | 1MB | 0.2124 | 0.1566 | +0.056 |
| 2048 | 184ms | 5MB | 0.2372 | 0.1699 | **+0.067** |
| 8192 | 196ms | 22MB | 0.2416 | 0.1943 | +0.047 |

Two things came out of this that contradicted the initial guess:

- **Raising the bucket count does not separate wanted from nonsense.** Sixteen times the
  dimensions moved the margin by less than the noise. The overlap is *real shared character
  n-grams* -- `flibbertigibbet wombat` genuinely shares `ibb`, `ert` and `omb` with source code
  -- not hash collisions. 2048 is the default because it wins on margin at a quarter of the disk
  of 8192, not because it is a round number.
- **The workable margin is about 0.07.** That is thin, which is why the floor is a configurable
  knob rather than a constant, and why it is documented as failing safe in both directions:
  too high costs dense recall on typos, too low admits weak matches, and BM25 answers on its own
  regardless. `RagLiveTest` asserts the separation directly -- on the Scala side, this failed the
  build on regression. The port of this test exists but is currently `@Disabled`, for
  reasons unrelated to this assertion (see [testing.md](testing.md)), so an encoder change that
  erodes the margin will not currently be caught by it here until that is resolved.

#### Invalidation is not optional here

Vectors from a different encoder are the right shape and meaningless: they search successfully
and rank nonsense, which is the only failure in this subsystem a user could not diagnose from
the output. So `save` writes the encoder's id (`hashing-v1:d2048:g3-5`) and dimension count into
the meta *itself*, overriding whatever the caller passed, because the code writing the sidecar is
the only thing entitled to describe it. `load` then refuses the vectors unless the id, the
dimension count and the row count all match, reports why through `vectorProblem`, and degrades to
BM25 -- with `/rag` saying `bm25 only` rather than `hybrid`, because silent degradation looks
exactly like a quality regression with no cause.

`rag_embedding: none` deletes the sidecar on the next save. Left behind, it would be read as
authoritative the moment an encoder was configured again, against chunks that had since changed.

**Row `i` is chunk `i`, and a count mismatch is corruption rather than something to trim to
fit.** Misaligned vectors would attribute every passage to the wrong file, with plausible text
under a wrong citation.

#### Performance, measured

Brute-force cosine, no ANN index. **3.4ms per hybrid query over 2,155 chunks**, asserted by a live
test with a 150ms ceiling. An approximate index would add a dependency, a build step, a tuning
surface and approximation to save time that is not being spent. Lucene or a vector database is
the upgrade path past roughly a million chunks.

Re-measured on this tree rather than carried over: the earlier figure, 1.5ms over 742 chunks,
was taken on the Scala repository when it was a third of this size. Cost per query grew
sub-linearly with the corpus, which is the property the brute-force decision rests on.

Indexing this repository with vectors takes a few seconds for ~2,150 chunks, so the encoder is
free in practice -- which is most of why re-encoding on every save is acceptable and no
incremental vector bookkeeping exists.

