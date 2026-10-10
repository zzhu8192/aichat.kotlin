# Templating and harness variables

Two client-side substitution mechanisms, applied in this order: `{{ }}` variables
first, then `<% %>` template code. The model never sees either.

## Harness variables

`{{name}}` interpolates facts the harness knows and the model cannot see. Deliberately a second,
lighter mechanism alongside `<% %>`: dropping the model name or the working directory into a
prompt should not start a compiler or be able to fail in interesting ways. Spelled `{{ }}`
because `${ }` collides with the shell and JS that prompts routinely contain, and because aichat
already uses this spelling in its role prompts.

`/vars` lists the table with live values. Roughly thirty variables across endpoint and model
(`model`, `model.id`, `endpoint`, `client`), context and usage (`context.limit`,
`context.percent`, `tokens.in/out/total`, `requests`, `stats`), session (`uptime`,
`session.started`, `tools`), settings (`reasoning`, `max_tokens`, `config.path`), machine
(`pwd`, `home`, `user`, `host`, `os`, `arch`, `shell`, `cmdline`), versions and time. `rate.*`
appears only when a rate limit is configured, so the table never advertises a variable that
would always read zero.

Design rules that matter:

- **Order: variables first, then `<% %>`.** That way a harness value can be used *inside*
  template code -- `<%= "{{model}}".toUpperCase %>` works -- which is the composition that makes
  the two features worth having separately.
- **Substituted values have `<%` neutralised to `<%%`.** Since variables expand before
  templating, an unguarded value containing `<%` would be executed as Groovy. `cmdline` can
  plausibly contain one. A harness fact must never become code.
- **An unknown but reference-shaped name is an error, with a suggestion.** Leaving `{{modle}}`
  in the prompt would send the model a brace expression and look like the feature was broken.
- **Braces around anything that is not reference-shaped pass through untouched**, so prose,
  JSON, and Handlebars meant for something else are safe. `\{{name}}` escapes a literal.
- **Nothing takes an argument and nothing shells out.** A variable that could hang or fail makes
  prompts unreliable; anything dynamic belongs in `<% %>`, which can already read files and run
  commands.
- Expansion is a single pass, so a value that itself contains `{{...}}` (as `cmdline` will, if
  the command line contained one) is not re-scanned.

## Templating

`<% %>` expands **on the client, before the request is built**. The model receives only the
resulting text and never sees a tag -- that is the entire point: assembling a prompt from files,
command output or computed values is the client's job.

    <% statements %>      Groovy statements, emitting nothing
    <%= expression %>     evaluated and interpolated
    <%-- comment --%>     dropped, but its newlines are kept so prose keeps its shape
    <%%                   a literal <%

Expansion is applied at the **input boundary** (`Repl.send`, `Main.singleHit`, and the
`--dry-run` path), not inside `Runner.ask`. That is deliberate: `/regenerate` then replays the
already-expanded text rather than evaluating the template again, and a second evaluation could
legitimately produce something different, which is not what re-sending should mean.

Implementation notes worth keeping:

- **Literals are emitted as escaped string literals**, not triple-quoted. A template containing
  `"""` would break triple quoting, and templates carrying code samples are the normal case.
- **Output goes to a bound `Sink`, not stdout.** Captured output is length-capped and would end
  up in the interpreter transcript; a sink is neither, so a template can be arbitrarily large.
  `Sink` is a named public class because `bindValue` binds by runtime type and the generated
  code has to typecheck against it.
- **One append per template line**, so generated and template line numbers stay in step. A
  compile failure is reported against the template line via the line map, and the generated
  program is printed annotated with template line numbers -- the user never wrote that code, so
  a raw diagnostic pointing into it is close to useless on its own.
- **No tags means no compiler.** `Template.hasTags` short-circuits, because most prompts are
  plain text and must not pay a second of compiler startup.
- Templates share the interpreter session with `groovy_eval`, so values cross between them.

Known limitation, shared with every templating language of this family: a `%>` inside a string
literal within a tag closes the tag early. Write it as `"%" + ">"`.

