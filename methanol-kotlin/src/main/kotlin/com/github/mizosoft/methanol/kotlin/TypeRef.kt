package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.TypeRef

/** Captures [T] within a [TypeRef]. */
inline fun <reified T> TypeRef() = object : TypeRef<T>() {}
