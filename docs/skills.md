# Skills

The full write-up is in [skills-design.md](skills-design.md).

`SKILL.md` in a directory: YAML frontmatter plus markdown instructions, loaded in three tiers.
Tier 1 is `name: description` injected every turn (~one line per skill), tier 2 is the body,
returned by the `skill` tool when the model asks, tier 3 is the bundled files, reached with
`read` and `exec` and costing nothing until read. That tiering is the whole idea: many skills
can be *available* for the price of a line each.

**Only the portable six frontmatter fields are implemented** -- `name`, `description`, `license`,
`compatibility`, `metadata`, `allowed-tools`. That is not a shortcut: Anthropic's own docs put
the portable subset at exactly those, and everything else in Claude Code (`context: fork`,
`agent`, `hooks`, `effort`, `model`, `paths`, `background`, `disable-model-invocation`) is a
Claude Code extension that fails validation on other surfaces. Unknown fields are collected and
shown by `/skills` so nothing is invisible, and are never injected -- a field we do not implement
must not reach the prompt and read as a promise.

**`allowed-tools` is reported as *encouraged*, never enforced.** The user's call: "I'll be happy
if the LLM actually decides to use the right tools." Enforcement would mean narrowing
`Runner.activeTools` for the duration of a skill, which means tracking when a skill is in
effect -- real state, for a constraint the model honours or does not regardless. A model reaching
for something else is a prompt problem, visible in the transcript and fixable in the file.

**The tool-vocabulary gap is closed with two lines, not new tool names.** A hub skill says "use
the Read tool" and "run this with Bash"; our tools are `read` and `exec`. So the tier-1 block
carries `Read -> read_to_context, Write -> write, ..., Bash -> exec`, built from
`Skills.ClaudeCodeNames`
and **filtered to the tools actually offered on that request** -- pointing the model at `read`
when no file tools are registered would be worse than saying nothing. Lowercase names were kept
because every other tool here is lowercase; a second naming convention for one subsystem is not
worth the compatibility it buys.

Other decisions worth keeping:

- **Three roots, nearest last.** `<config dir>/skills`, then `~/.claude/skills`, then
  `.claude/skills` walking up from the working directory. An existing Claude Code install is read
  in place, so nothing has to be copied, and a project can override a personal skill -- which is
  the only reason to have more than one root. Nested directories are walked, because plugin and
  hub layouts nest.
- **A broken `SKILL.md` is reported, not dropped.** Same rule as an over-budget memory. Invalid
  YAML is reported *as* invalid YAML rather than as a missing `name`, which is what a
  `Conf.empty` fallback said and which sends the user looking in the wrong place.
- **Tier 1 is budgeted and names what it dropped.** Twenty skills at a line each is fine; two
  hundred from a hub install is not, and `skills_max_chars` sheds from the tail and says which.
- **Tier 2 tells the model where it is standing.** The result carries the absolute skill
  directory *and* a listing of the bundled files, because a body saying "run `scripts/fill.py`"
  refers to a directory the model has otherwise never been told about -- without the listing it
  would not know tier 3 existed.
- **`$ARGUMENTS` is substituted; the `arguments:` field is not implemented.** One string replace,
  and it is what hub skills actually use. Unsupplied, it becomes empty rather than being left in
  the text where the model reads it as a literal instruction. `/skill <name> <args>` appends the
  args as a task line only when the body does *not* interpolate them, so they are never delivered
  twice nor silently dropped.
- **The `skill` tool is registered only when a skill exists**, the same rule as `search` and an
  active index: a schema the model can only fail to use still costs tokens every request.
- **Tests point every root at a temp directory.** The defaults include `~/.claude/skills` and
  every `.claude/skills` up to the filesystem root, so a developer who uses Claude Code has a
  populated one -- exactly the hazard `TestIsolation` exists for, which now disables skills too.

Surface: the `skill` tool, `/skills` (what was found, with scope, license, bundled count and
ignored fields), `/skill <name> [args]` (deliberate invocation, body sent as the turn),
`--list-skills` and `--skill <name>`. `examples/skills/scala-review/` is a worked example that
exercises all three tiers.

**Skills and agents sit on opposite sides of one line, and both earn their place.** A skill is
context the model pulls in and then interprets: no control flow, cheap to write, portable to
other harnesses, and its failure mode is being silently *not used* because the description did
not match. An agent is a program in `<% %>` that calls the model: exact control flow, typed
results, kotllm-only. A skill makes the model better at a task; an agent makes the task happen
the same way twice.

Not built, deliberately: enforcement of `allowed-tools`, any provenance or sandbox checking of
installed skills (the user deferred security; the honest statement is that a skill is arbitrary
instructions plus arbitrary scripts, which is what Anthropic's own docs say), and every Claude
Code extension field.

