/** Kotlin DSL for Methanol. */
module methanol.kotlin {
  requires methanol;
  requires kotlinx.coroutines.core;
  requires kotlinx.serialization.core;

  exports com.github.mizosoft.methanol.kotlin;
}
