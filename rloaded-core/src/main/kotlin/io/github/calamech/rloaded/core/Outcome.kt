package io.github.calamech.rloaded.core

/**
 * The result of an operation that can fail in an expected way, described by a typed error [E].
 *
 * Exceptions are kept for bugs; anything a caller should handle is an [Err].
 */
public sealed interface Outcome<out T, out E> {
    /** The operation succeeded with [value]. */
    public data class Ok<out T>(
        val value: T,
    ) : Outcome<T, Nothing>

    /** The operation failed with [error]. */
    public data class Err<out E>(
        val error: E,
    ) : Outcome<Nothing, E>
}

/** Applies [transform] to a successful value and keeps errors as they are. */
public inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> =
    when (this) {
        is Outcome.Ok -> Outcome.Ok(transform(value))
        is Outcome.Err -> this
    }

/** Chains an operation that can itself fail, starting from a successful value. */
public inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> =
    when (this) {
        is Outcome.Ok -> transform(value)
        is Outcome.Err -> this
    }
