package com.github.mizosoft.methanol.kotlin

import kotlin.annotation.AnnotationRetention.BINARY

/**
 * Marks a type as a specification for configuring some object or property, typically by invoking a
 * user-provided block with the spec as its receiver.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
@Retention(BINARY)
@MustBeDocumented
annotation class Spec

/** A [Spec] that creates an object of type [T], typically after invoking a spec configuration block. */
@Spec
interface FactorySpec<T> {
  fun make(): T
}

/** A [Spec] for configuring map-like properties with string keys and multi string values. */
@Spec
interface StringNameMultiStringValueSpec {
  @Deprecated(
    message = "You probably mean to call a different `to`. Make sure the key is a String and the value is either a String or a List<String>.",
    level = DeprecationLevel.ERROR
  )
  infix fun <A, B> A.to(that: B): Pair<A, B> =
    error("You probably mean to call a different `to`. Make sure the key is a String and the value is either a String or a List<String>.")

  infix fun String.to(value: String)

  infix fun String.to(values: List<String>)
}
