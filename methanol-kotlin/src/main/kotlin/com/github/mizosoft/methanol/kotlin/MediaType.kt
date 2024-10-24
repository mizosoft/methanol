package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.MediaType

/** Parses the given string into a [MediaType]. */
fun String.toMediaType(): MediaType = MediaType.parse(this)
