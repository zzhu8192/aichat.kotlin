// A worked example of a kotllm tool. Copy this file into
// <config dir>/tools/ and it is offered to the model on the next start --
// no build step, no jar, no restart of anything but kotllm itself.
//
// The contract is one sentence: **the file's last expression must be a
// kotllm.tools.Tool.** It is evaluated in the same Groovy session `groovy_eval`
// uses, so there is no compiler to wait for and no cache to invalidate.
//
// You write the JSON Schema yourself. Nothing is derived by reflection, which
// means nothing can be derived *wrongly*: what you write below is exactly what
// the model is shown.

import kotllm.tools.Tool
import kotllm.tools.ToolResult

// You write the JSON Schema yourself. Nothing is derived by reflection, which
// means nothing can be derived *wrongly*: what you write below is exactly what
// the model is shown. `Tool.schema` parses it once, here, when the file loads.
def schema = Tool.schema('''
{
  "type": "object",
  "properties": {
    "text":   { "type": "string",  "description": "The text to count words in." },
    "unique": { "type": "boolean", "description": "Count distinct words instead of every occurrence." }
  },
  "required": ["text"]
}
''')

// The constructor is (name, description, parameters, preview, run, readOnly).
new Tool(
    "word_count",
    "Counts words in `text`. unique=true counts distinct words.",
    schema,
    // The preview is what the approval prompt shows, so it has to say what the
    // call would actually do rather than name the tool again.
    { args -> "count words in " + (Tool.optStr(args, "text")?.take(40) ?: "") },
    { args ->
        // `Tool.optStr` rather than `Tool.str`, deliberately: `str` returns an
        // `Outcome`, whose `fold`/`map`/`flatMap` are Kotlin *extension*
        // functions. Those compile to static methods that Groovy cannot see as
        // members, so `.fold { } { }` fails at call time with "No signature of
        // method: fold". Check for the value and say so yourself.
        def text = Tool.optStr(args, "text")
        if (text == null) {
            new ToolResult("missing required argument 'text'", true)
        } else {
            def words = text.trim().split(/\s+/).findAll { it }
            def n = Tool.optBool(args, "unique") ? words*.toLowerCase().unique().size() : words.size()
            new ToolResult(n.toString(), false)
        }
    },
    // Counting cannot change anything, so the confirm prompt is skipped. Leave
    // this false for anything that writes, runs a command or spends money.
    true
)
