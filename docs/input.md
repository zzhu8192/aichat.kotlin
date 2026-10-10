# Input

Everything on the way in: `-f`/`/file` sources, images, HTML conversion, and
keyless web search with its `fetch` companion.

## Multi-form input

`-f` / `/file` resolves files, directories, URLs, shell commands in backticks, and `%%` for the
last reply, using aichat's vocabulary so muscle memory carries over. `--` separates sources
from the message: `/file src/ README.md -- review this`.

Guards exist because the failure mode is wasting a whole context window in one keystroke:

- **Skips are always reported, never swallowed.** Believing the model saw a file it never
  received is worse than sending nothing.
- Binary files are detected by a NUL byte in the first 8KB and skipped -- sending a jar as text
  spends the budget for no information.
- Three separate caps (`input_max_file_chars`, `input_max_total_chars`, `input_max_files`), and
  hitting one says which.
- Directory walks skip build output and dot-directories (`target`, `node_modules`, `.git`, ...),
  which are nearly never the intent and would dominate the budget.
- Fences grow to outrun any backtick run in the content, so a file containing a code fence does
  not close the block early.
- `document_loaders` is aichat-compatible (`pdf: 'pdftotext $1 -'`), so a format we cannot read
  as text can be converted on the way in.

`-f` with no text is still a real request, so it must not fall through to interactive mode --
`Main.composeInput` returns null only when there is genuinely nothing to send.

## Images

`-f shot.png` attaches an image as an `image_url` content part. `png`, `jpg`,
`jpeg`, `webp`, `gif` -- the set every endpoint we target accepts, rather than
"anything with an image mime type", which would send a tiff to be refused and
charged for.

- **`supports_vision` is finally read.** It was parsed and used by nothing
  before this. `Runner.attachable` drops images for a model that does not
  declare vision and says so, naming the key -- because the default is `false`,
  so the first screenshot anyone attaches would otherwise vanish or come back
  as an opaque 400 after uploading a megabyte.
- **An image never becomes prompt text.** It travels beside the text from
  `Media` through `Outcome.Send` to `Message.user(text, images)`, and
  `Message.encode` switches to the array-of-parts form *only* when there is an
  image. Input is still a bare string otherwise, which is what every
  OpenAI-compatible server accepts.
- **Base64 is kept away from the estimator.** A data URL is hundreds of
  thousands of characters that cost a fixed handful of tokens, so
  `Runner.measure` subtracts the payload length, charges a flat
  `ImageTokenAllowance` (800) per image, and **skips calibration entirely** on
  any request carrying one. Counting it would teach a ratio wrong by two orders
  of magnitude -- the same class of error as the 1.37 chars/token incident.
- **`--dry-run` elides the payload.** `Runner.elideImageData` replaces it with
  its length. A megabyte of base64 on one line does not just bury the request,
  it can wedge a terminal, and the only facts worth reading are that an image
  is there and how big it is.
- Its own cap, `input_max_image_bytes` (5MB before base64), because the
  character budget exists to bound text. Images count against
  `input_max_files`.

## HTML to markdown

The largest avoidable token cost in the input path: a documentation page is
80-95% markup. Measured on the Groovy language documentation, 46,945 characters
became 11,132 -- 76% smaller -- and the saving is *reported* on the block,
because what the model receives is not what the server sent.

`input/Html.kt` is a hand-written tokeniser, applied to fetched URLs, local
`.html` files and the `fetch` tool, switchable with `html_to_markdown: false`.

- **A tokeniser, not a regex pipeline.** The rules need to know whether they
  are inside `<pre>`, where whitespace is content, and how deep a list is, and
  no chain of regexes can track that. **Equally not a parser**: unbalanced tags
  are normal in the wild, so every rule degrades to slightly wrong spacing
  rather than to an exception.
- **No dependency.** jsoup or flexmark would widen the classpath handed to the
  LLM and inflate the injected manifest to serve one input path.
- Scripts, styles and `svg` are dropped whole; `img` keeps its alt text and
  loses its URL, which the model cannot fetch anyway.
- **A `<` that does not start a tag is text.** `indexOf('<', i)` finds the
  character the scanner is standing on, consumes nothing and spins for ever.
  That hung a test run for ten minutes; it is `i + 1` now and there is a test
  with `<<>>` in it.
- `looksLikeHtml` is deliberately conservative -- a doctype, an `<html>` root,
  or three distinct block tags -- because a false positive strips the markup
  out of a file somebody sent *as* HTML source. The declared content type wins
  over the sniff, including `text/plain`, for the same reason.

## Web search, and why it is keyless

No account, no API key, nothing to sign up for -- that was the requirement, and
it is worth being honest about what it costs, because the tool has two halves
with very different reliability.

**Documented JSON APIs, which will keep working:** Stack Exchange (300
requests/day unauthenticated, and the score plus accepted-answer flag are what
make a result worth fetching), the MediaWiki search API, Maven Central, and
GitHub repository search. GitHub *code* search needs authentication, so it is
not offered rather than returning a 401 the model cannot act on; a
`GITHUB_TOKEN` in the environment is used when present purely to raise the rate
limit.

**Scraped HTML, which will eventually break:** DuckDuckGo's `lite` and `html`
endpoints. No general-purpose engine offers a keyless, terms-clean, stable API
-- Google's Custom Search and Bing's Web Search both need a key and an account,
and scraping `google.com/search` hits a consent wall and then a CAPTCHA. So
general web search is **opt-in** (`web_search: { provider: ddg-lite }`) while
the four sources need no opt-in at all. That asymmetry is the decision:
a general search is the model inventing a query and sending it somewhere, and
read-only tools do not stop to ask.

Load-bearing details:

- **Breakage is reported as breakage.** No parseable results says "this
  provider is scraped HTML, the layout may have changed" rather than "no
  results", because those mean very different things to whoever has to fix it.
- **The snippet regex closes on a backreference to its own tag.** `(.*?)</[a-z]+>`
  stops at the first nested `</b>`, and DuckDuckGo bolds the query terms inside
  the snippet -- so every result was truncated at the first highlighted word.
- **The DDG redirect is unwrapped** (`/l/?uddg=...`), because the model needs a
  real URL to `fetch` and the redirect is a second request they can rate limit.
- **Maven has two routes.** `search.maven.org` is the searchable index and
  times out from behind some corporate proxies -- it does from this machine --
  while `repo1.maven.org` is static file hosting reachable wherever Maven
  itself works. Coordinates therefore go straight to `maven-metadata.xml`,
  which is faster and exact, and a `_2.13` suffix is tried when the plain
  artifact is absent.
- **`sites:` is told to the model, not enforced.** A filter would silently
  return nothing for a question the list did not anticipate; naming the domains
  in the tool description lets the model choose a `site:`.
- The tests exercise only the pure parts. A test that queried DuckDuckGo would
  fail on a plane, in CI, and on the day the markup changes -- which is the day
  the parser needs *editing*, not the day everyone's build should break.
  `scripts/smoke.sc` is the live check, run by hand.

### TLS interception breaks this before anything else does

Behind a corporate proxy, TLS is terminated and re-signed with a private CA
that the OS trusts and the JVM does not, because the JVM ships its own
`cacerts`. The failure is per-host and looks like a bug here:

    PKIX path building failed: unable to find valid certification path

On this machine `api.github.com` was not intercepted and worked while
DuckDuckGo, Wikipedia, Maven Central and Stack Exchange all failed. `util/Tls`
therefore builds a **union** trust manager -- the JDK store, plus the
`Windows-ROOT` store when present, plus anything named by `KOTLLM_TRUSTSTORE`
(a `.pem` or a keystore) -- and accepts a chain any member accepts, which is
the question a browser answers. Nothing is disabled: a certificate nobody
vouches for is still refused, and `Tls.explain` turns the opaque failure into
the remedy. `/info` reports which stores are in play.

