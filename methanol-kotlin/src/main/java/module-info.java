/** Kotlin DSL for Methanol. */
module methanol.kotlin {
  requires transitive methanol;
  requires kotlin.stdlib;
  requires kotlinx.coroutines.core;
  requires kotlinx.serialization.core;

  exports com.github.mizosoft.methanol.kotlin;
}
