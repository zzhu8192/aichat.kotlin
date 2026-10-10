package kotllm.util

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Minimal YAML access layer.
  *
  * Config is read through [[Conf]] rather than mapped onto case classes by a
  * derivation macro, because the aichat-compatible format carries keys we do
  * not implement -- and will grow more over time. An unknown key must never be
  * a load error. Everything is looked up by name, and a missing or mistyped
  * value falls back to the caller's default.
  */
object Yaml {

  fun loadFile(path: Path): Conf =
    loadString(String(Files.readAllBytes(path), StandardCharsets.UTF_8))

  fun loadString(text: String): Conf {
    val loaded = org.yaml.snakeyaml.Yaml().load<Any>(text)
    return when (loaded) {
      is Map<*, *> -> Conf(asScalaMap(loaded))
      null -> Conf.empty
      else -> throw IllegalArgumentException(
        "expected a YAML mapping at the top level, got ${loaded.javaClass.simpleName}"
      )
    }
  }

  // Nullable in and out. A YAML key written `foo:` with nothing after it
  // loads as null, and Scala's `case other => other` passed that straight
  // through for `Conf.str` to read as absent. A `!!` here turned the shipped
  // example config into "cannot parse: NullPointerException".
  private fun convert(value: Any?): Any? = when (value) {
    is Map<*, *> -> asScalaMap(value)
    is List<*> -> value.map { convert(it) }
    else -> value
  }

  // `Map`, not `java.util.Map`: Kotlin MAPS the JDK collection types onto
  // its own, so naming the Java one is an error rather than a synonym.
  fun asScalaMap(m: Map<*, *>): Map<String, Any?> =
    m.entries.associate { (k, v) -> k.toString() to convert(v) }
}

/** A YAML mapping with forgiving, type-coercing accessors. */
data class Conf(val raw: Map<String, Any?>) {

  fun contains(key: String): Boolean = raw.containsKey(key)

  val keys: Set<String> get() = raw.keys

  fun str(key: String): String? = raw[key]?.let {
    when (it) {
      is String -> if (it.isEmpty()) null else it
      else -> it.toString()
    }
  }

  fun bool(key: String): Boolean? = raw[key]?.let {
    when (it) {
      is Boolean -> it
      is String -> when (it.trim().lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
      }
      else -> null
    }
  }

  fun int(key: String): Int? = raw[key]?.let {
    when (it) {
      is Number -> it.toInt()
      is String -> it.trim().toIntOrNull()
      else -> null
    }
  }

  fun double(key: String): Double? = raw[key]?.let {
    when (it) {
      is Number -> it.toDouble()
      is String -> it.trim().toDoubleOrNull()
      else -> null
    }
  }

  /** A list of strings, also accepting a single scalar or a comma-separated
    * string -- all three spellings show up in hand-written config. */
  fun strList(key: String): List<String> = when (val value = raw[key]) {
    is List<*> -> value.filterNotNull().map { it.toString() }
    is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    null -> emptyList()
    else -> listOf(value.toString())
  }

  /** A mapping of name to scalar, for the small name tables in config.
    *
    * Nested mappings and lists are dropped rather than stringified: the shape
    * is wrong for the caller either way, and `alias -> Map(...)` rendered as
    * its `toString` would be a model id nobody could debug.
    */
  fun strMap(key: String): Map<String, String> = when (val value = raw[key]) {
    is Map<*, *> -> value.entries.mapNotNull { (k, v) ->
      if (v != null && v !is Map<*, *> && v !is List<*>) {
        k.toString().trim() to v.toString().trim()
      } else {
        null
      }
    }.toMap()
    else -> emptyMap()
  }

  fun sub(key: String): Conf? = when (val value = raw[key]) {
    is Map<*, *> -> @Suppress("UNCHECKED_CAST")
    Conf(value as Map<String, Any>)
    else -> null
  }

  /** A list of nested mappings; non-mapping entries are skipped. */
  fun subs(key: String): List<Conf> = when (val value = raw[key]) {
    is List<*> -> value.mapNotNull { item ->
      if (item is Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        Conf(item as Map<String, Any>)
      } else {
        null
      }
    }
    else -> emptyList()
  }

  companion object {
    val empty: Conf = Conf(emptyMap())
  }
}
