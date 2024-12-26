/*
 * Copyright (c) 2024 Moataz Hussein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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

/**
 * A [Spec] in the context of which Kotlin's [kotlin.to] is disabled to avoid conflicts with
 * similarly named spec-defined functions.
 */
@Spec
interface PairCreationDisablingSpec {
  @Deprecated(
    message = "You probably mean to call a different `to`. Make sure this `to` corresponds to one that is defined by this spec.",
    level = DeprecationLevel.ERROR
  )
  infix fun <A, B> A.to(that: B): Pair<A, B> =
    error("You probably mean to call a different `to`. Make sure this `to` corresponds to one that is defined by this spec.")
}

/** A [Spec] for configuring map-like properties with string keys and multi string values. */
@Spec
interface StringNameMultiStringValueSpec : PairCreationDisablingSpec {
  infix fun String.to(value: String)

  infix fun String.to(values: List<String>)
}
