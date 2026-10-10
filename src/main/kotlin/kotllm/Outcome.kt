package kotllm

/** What `Either[String, A]` was in the Scala tree, and it is used the same way.
 *
 * 140 call sites depend on this shape, so it is defined once here rather than
 * left to each translated file to improvise. Not `kotlin.Result`: that carries
 * a `Throwable`, and almost every failure in this codebase is a sentence meant
 * for a person or a model to read, not an exception anyone throws.
 *
 * No Arrow. One sealed interface and four helpers cover every use in the
 * source, and a dependency that shows up in every signature is not worth it
 * for that.
 */
sealed interface Outcome<out A> {
    data class Ok<out A>(val value: A) : Outcome<A>
    data class Err(val message: String) : Outcome<Nothing>
}

fun <A> ok(value: A): Outcome<A> = Outcome.Ok(value)

fun err(message: String): Outcome<Nothing> = Outcome.Err(message)

val Outcome<*>.isOk: Boolean get() = this is Outcome.Ok

/** The value, or null. The Scala side spelled this `.toOption`. */
fun <A> Outcome<A>.orNull(): A? = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> null
}

fun <A> Outcome<A>.getOrElse(fallback: (String) -> @UnsafeVariance A): A = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fallback(message)
}

inline fun <A, B> Outcome<A>.map(f: (A) -> B): Outcome<B> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

/** Replaces the `for { a <- x; b <- y }` comprehensions, which Kotlin has no
 * syntax for. Chained `flatMap` reads worse than Scala's version did; an early
 * `return` on the error case usually reads better still, and both are fine. */
inline fun <A, B> Outcome<A>.flatMap(f: (A) -> Outcome<B>): Outcome<B> = when (this) {
    is Outcome.Ok -> f(value)
    is Outcome.Err -> this
}

/** Collapses both arms to one type, which is what every call site that renders
 * a failure to the user or to a model actually wants. `Either.fold` in Scala
 * took the error case first, and this keeps that order so a translated call
 * site does not silently swap its arms. */
inline fun <A, B> Outcome<A>.fold(onErr: (String) -> B, onOk: (A) -> B): B = when (this) {
    is Outcome.Ok -> onOk(value)
    is Outcome.Err -> onErr(message)
}

/** `Try(...)` at a boundary, turned into a message the caller can show. */
inline fun <A> attempt(describe: (Throwable) -> String = { it.message ?: it::class.simpleName ?: "failed" }, body: () -> A): Outcome<A> =
    try {
        Outcome.Ok(body())
    } catch (e: Exception) {
        Outcome.Err(describe(e))
    }
