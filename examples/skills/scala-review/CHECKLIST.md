# The rubric

Tier 3: this file is not in the model's context until it reads it.

## Must fix

- **Scala 3 syntax.** `given`, `extension`, `enum`, significant indentation,
  `?=>`. This project is Scala 2.13 only and none of it compiles.
- **Thrown exceptions across a module boundary.** Prefer `Either` or `Try`; the
  REPL loop is the single place failures are rendered to the user.
- **A wire decoder that assumes a field exists**, or that `content` is a String.
  The gateways evolve underneath us; every decoder must tolerate unknown fields
  and both `String` and `Array[Part]`.
- **A hard-coded Unicode glyph.** Go through `util/Glyphs`, which falls back to
  ASCII on a console that cannot encode block and braille characters.
- **A print to stdout while the pinned status region is active.** Wrap it in
  `Chrome.whilePrinting`, or the footer etches itself into the scrollback.

## Worth considering

- A comment that restates the code instead of saying why the code is that way.
- A new dependency. The fat jar classpath is the standard library handed to the
  LLM, so every addition widens what generated code may import.
- A cap or truncation that does not report what it withheld.
- A tool result that omits a `tool_call_id` answer on an abandoned round trip.

## Fine

- Long comment blocks explaining a decision and the failure that motivated it.
  That is the house style, not clutter.
