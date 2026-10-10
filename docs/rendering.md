# Rendering

Terminal markdown, LaTeX, and the glyph fallbacks that keep both readable on a
console that cannot encode Unicode.

### Terminal encoding

Java 17 does not default stdout to UTF-8 the way 18+ does; on Windows the console encoding
follows the active codepage. Block-drawing and braille glyphs printed to a cp1252/cp437 console
come out as `?`. `util/Glyphs` asks the actual output charset what it can encode and falls back
to an ASCII ramp, bar, arrow and spinner. **Never hard-code these glyphs** -- go through
`Glyphs`, and in tests derive expectations from `Glyphs` too, or they fail on exactly the
terminals the fallback exists for. `dividerChar` was the one glyph that ignored this and it cost
a bug; see "The alternate character set" under REPL chrome.

## LaTeX

Models answering anything mathematical emit LaTeX whether or not it was asked
for, so the choice was never "render maths or not" -- it was between showing
`\frac{4x}{x+2}=8` and showing `(4x)/(x+2)=8`. The second is an answer; the
first is the renderer visibly not handling something.

```
> 4x / (x+2) = 8

  (4x)/(x+2)=8

  Multiply both sides by x+2:

  4x=8(x+2)
  4x=8x+16
  -4x=16

  [ x=-4 ]

  Since x != -2, this solution is valid.
```

**Not a typesetter, and it does not try to be.** No stacked fractions, no
matrices in box characters, no integral signs spanning rows. A fraction
becomes a division, a superscript becomes a superscript digit where Unicode
has one, and a command with no plain-text form keeps its name rather than
vanishing -- a silently dropped operator changes what the answer says.

Handled: `\[ \]`, `\( \)`, `$$ $$` and `$ $` delimiters; `\frac` /
`\dfrac` / `\tfrac`; `\sqrt`; `\boxed`; `\text` and the font commands;
`\left` / `\right`; spacing commands; `^` and `_` runs; and about ninety
symbols and Greek letters.

Things that are load-bearing:

- **Every substitution has an ASCII fallback, gated on `Glyphs.unicode`.**
  This is the file that would break that rule most easily, and the cost is
  not cosmetic: `≠` on a cp437 console arrives as `?`, which turns a correct
  answer into a wrong one. The fallbacks are chosen to stay *unambiguous*
  rather than to look similar -- `\leq` falls back to `<=`, never to `=`.
  Seen live in the same session: the model's own raw `≠` came through piped
  output as `?` while `\neq` rendered as `!=`, which is the whole argument
  for routing maths through this rather than trusting what arrives.
- **A compound numerator is parenthesised.** `\frac{4x}{x+2}` is
  `(4x)/(x+2)`, not `4x/(x+2)` -- the second is only right if the reader
  applies precedence, and the first is right on sight. A bare number or a
  bare identifier is left alone.
- **A fence wins over everything.** A `\frac` inside a code block is source
  someone is reading, not maths to render.
- **`$...$` converts only when it cannot be currency** -- when it contains a
  backslash command, a caret or an underscore. Otherwise `it costs $5 and
  $10` becomes a formula. Erring cautiously leaves `$x$` unrendered; erring
  the other way eats a price list.
- **Display maths is indented, not coloured.** It is usually the answer, and
  dimming the answer to distinguish it from the prose is the wrong way round.
- **It is stateful across lines**, because that is how streaming delivers it:
  `\[`, the body and `\]` each arrive as separate lines.
- **`latex` is separate from `highlight`**, because they are different
  questions -- one is about colour, the other about what the characters say.
  Someone piping output may well want the maths readable and the escapes
  gone.

**Forcing `KOTLLM_UNICODE` both ways is how the spec is actually run**, and it
earned that immediately: with Unicode off the suite passed, and turning it on
exposed an assertion that `x^{n+1}` stays literal. It does not -- `n`, `+` and
`1` all have superscript forms, so it renders `xⁿ⁺¹`, which is better than
what the test demanded. The test was wrong, not the code, and a default test
environment that cannot encode Unicode would never have said so.

## Emoji

`:shortcode:` text -- Slack and GitHub's convention -- expands to the Unicode
emoji it names: `:fire:` becomes 🔥.

The table is [gemoji](https://github.com/github/gemoji)'s (MIT; see
`THIRD_PARTY_NOTICES.md`), vendored as a flat `shortcode<TAB>emoji` resource
rather than a dependency, since it is data and the fat jar already carries
everything the model sees. `kotllm.render.Emoji` is the lookup.

Two directions, both at submit/render time rather than live as you type:

- **What you type.** The REPL expands shortcodes in the line (or fenced
  multi-line body) it is about to send, before templating and before it is
  echoed into the transcript -- so both you and the model see the emoji, not
  the shortcode.
- **What the model writes.** `Markdown`'s line renderer expands shortcodes the
  same way it converts LaTeX, so a model that emits `:tada:` as text renders
  the emoji too. Models mostly emit real Unicode emoji directly rather than
  shortcodes, so this mainly covers the models that do not.

Always on -- there is no setting for it, matching the rest of this package.

Gated on `Glyphs.unicode`, same as everything else here, but with a different
fallback: an emoji has no ASCII equivalent the way `\neq` has `!=`, so on a
console that cannot encode it the shortcode is left as literal text rather
than substituted into a `?`.

**A fence wins over emoji too.** `:shrug:` inside a code block is source
someone is reading, same rule as LaTeX and for the same reason.

An unrecognised `:word:` -- gemoji does not have it, or it was never meant as
a shortcode -- is left exactly as written.

### ASCII emoticons

`:)`, `:-)`, `:D`, `;)` and the like are not gemoji's business -- it only
knows named shortcodes -- so they are a second, small, hand-picked table in
the same file: `:-)`/`:)` to 🙂, `:-(`/`:(` to 🙁, `:-D`/`:D` to 😃, `;-)`/`;)`
to 😉, `:-P`/`:P`/`:-p`/`:p` to 😛, `:'(` to 😢, `:-O`/`:O`/`:-o`/`:o` to 😮.

These only count at a word boundary -- whitespace or start/end of line on one
side, whitespace/end/`.,!?;` on the other. `x:)y` is not a smiley, and neither
is a token in code that happens to end `:D`. Prose typed by a person does not
need anything stricter than that.

### Picking one: the candidate list

The same thing `/` does for commands. Type two characters of a shortcode and
the list appears on its own -- no key to press:

```
>  :sm
:small_airplane: 🛩️           :smiley_cat: 😺
:small_blue_diamond: 🔹       :smiling_face_with_tear: 🥲
:small_red_triangle: 🔺       :smiling_imp: 😈
:smile: 😄                    :smirk: 😏
:smile_cat: 😸                :smoking: 🚬
:smiley: 😃
```

Every character after that narrows what is on screen. Tab lists too. Unlike
command completion this is not restricted to the first word: emoji land
anywhere in a sentence, so the list keys off the word behind the cursor
rather than the start of the line.

**Re-listed on every keystroke, not just the one that crosses the
threshold.** Listing once and stopping looks like it works and does not: the
next character redraws the line, which wipes the list, so it appeared at
`:ha` and vanished at `:hai` with `:haircut:` still sitting in the matches.
JLine redraws the list in place rather than printing a second one, so there
is no stack of stale lists to pay for this.

**Down steps straight into the list.** No Tab first: the list is already on
screen, so the obvious key is the one that works. Arrows move the highlight
-- down and right forward, up and left back -- Shift-Tab goes back too, and
Enter takes the highlighted one. A second Tab also opens the menu, and the
first Tab still only lists, which is the behaviour the command menu was
careful to keep: nothing is inserted until it has been chosen.

Up and Down are bound in the `main` keymap, where they are normally history
navigation, so they only divert to the menu **when the cursor is on a
half-typed `:shortcode` or a leading `/command`** -- the two things that put
a list on screen. Everywhere else they recall history exactly as before,
which is the condition that makes rebinding them safe. `3:2` is a ratio, not
a shortcode, and Down leaves it alone.

**One predicate decides all of it**, `ReplCompleter.wantsList`: what the
as-you-type key re-lists, what the arrows step into, and what the completer
offers. Three callers asking slightly different questions is how the `/`
list ended up never narrowing while the emoji list did, and how bare `/`
ended up with a list on screen that Down refused to enter -- falling through
to history, which replaces the line, so the menu appeared to be cleared by
the arrow keys.

All of this applies to `/` commands identically. `/a` narrows to `/agent`
as it is typed, and because that is the only `/a` command it simply
completes.

The arrows needed binding. JLine's `menu` keymap ships with Tab, Shift-Tab
and Enter but leaves the arrows on ordinary cursor movement, so pressing one
dropped out of the menu and left the tail of its own escape sequence in the
line -- the stray `B` from `ESC [ B`. `MenuKeys` binds all four, in both the
normal (`ESC [ A`) and application-cursor (`ESC O A`) encodings, since JLine
switches the keypad into application mode while it reads and binding one
form works only half the time. It binds into the shared `menu` keymap, so
**`/` command menus became navigable at the same time** -- the arrows were
dead in both.

### Colours

The menu is drawn in the terminal's own colours: no background, the
selected row in **reverse video**, the matched prefix in cyan like the plain
list.

Left to itself JLine paints the menu list on `bg:bright-magenta`, which is
a deliberate default and not a bug, but it is the only thing in kotllm that
paints its own background -- and a REPL that has otherwise left the colour
scheme alone should not start. Reverse video rather than a colour for the
selection, because it is the one highlight legible on a light theme and a
dark one without knowing which is in use.

`MenuKeysTest` asserts the magenta is gone, and -- because asserting the
absence of a colour proves nothing if it was never there -- asserts first
that JLine really does emit it when left alone.

Accepting a candidate inserts the **shortcode text**; submit-time expansion
turns it into the emoji when the line is sent.

### Ordering: what you actually use

The offer is capped, so which candidates survive the cap is the real
question, and alphabetical chooses badly: `:a` would spend the whole budget
inside the a's.

So the list is ordered by **how often you have sent each shortcode**, ties
alphabetical. Counts live in `emoji-usage.tsv` in the config directory,
beside `history.txt` and much the same kind of record.

Counted **on send, not on completion**: accepting a candidate is a guess at
intent, whereas a shortcode that went to the model in a real message is a
fact -- and it means names typed out in full, without ever opening the list,
still teach it. Only what you type is counted; the same expansion runs over
model output, and counting that would rank the model's habits instead of
yours.

Three things here are load-bearing, and each of them is a bug that shipped
first. See [history](history.md#completion) for the evidence.

- **The candidate's value is the shortcode, not the emoji.** JLine filters
  candidates by matching their *value* against the word being completed, and
  `🔥` does not start with `:fir`. Offering the glyph discards every
  candidate and Tab rings the bell.
- **The offer is capped** at `ReplCompleter.MaxEmojiCandidates`. A one-letter
  prefix matches up to 219, JLine's `list-max` suppresses a list that long
  without saying anything, and the key looks dead.
- **The list is *not* drawn with the pinned region suspended.** The rule
  everywhere else in the REPL is that writing to stdout suspends the
  reserved rows first, and completion is the exception: JLine's own display
  draws the list and already knows about the `Status` region it shares the
  screen with. Suspend underneath it and the restore repaints over the list
  -- completion runs cleanly and draws nothing.

Auto-listing costs a widget on every alphanumeric key, which is why that
widget does almost nothing: insert the character, look at the word behind the
cursor, stop. It fires once, when the shortcode reaches two characters,
rather than on every later keystroke -- each list is printed, so re-listing
per keystroke walks the transcript up the screen.

One JLine option was tried and rejected: `MENU_COMPLETE` selects the first
candidate on the *first* Tab instead of showing the list, which is the
"silently inserting the first one" the command menu already turned down, and
the first of 219 is never the one wanted. `AUTO_MENU` gives the selectable
menu without that -- it changes only the second Tab, which is why enabling
it globally was safe.

