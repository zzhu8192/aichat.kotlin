package kotllm.prompt

/** The shapes a prompt template loops over.
  *
  * Top level and plain on purpose. They are named inside `agents/chat.md`, so
  * they have to be types the interpreter can resolve, and they are read by
  * someone editing a prompt rather than writing Scala -- which is why every
  * field is a `String` or an `Int` and nothing here is an `Option`. A template
  * author should be able to write `<%= m.name %>` and be right.
  */
// Every property here carries `@get:JvmName`. These types are named inside
// `agents/chat.md`, whose code IMain compiles as Scala, and a template author
// writes `<%= m.name %>`. A plain Kotlin `val name` compiles to `getName()`,
// which Scala cannot call as `.name` -- proved with a live scalac run, not
// assumed. Renaming the getter keeps `.name` working on both sides.

data class MemoryItem(@get:JvmName("name") val name: String, @get:JvmName("scope") val scope: String, @get:JvmName("text") val text: String)

/** A skill, a user tool, or a memory listed by name only: the three sections
  * that are a name and one line about when it applies. */
data class NamedItem(@get:JvmName("name") val name: String, @get:JvmName("description") val description: String)

data class TaskItem(@get:JvmName("id") val id: Int, @get:JvmName("text") val text: String, @get:JvmName("status") val status: String, @get:JvmName("marker") val marker: String)

data class Library(@get:JvmName("name") val name: String, @get:JvmName("hint") val hint: String)
