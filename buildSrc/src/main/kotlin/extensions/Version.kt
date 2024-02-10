package extensions

data class Version(
  val major: Int,
  val minor: Int,
  val patch: Int,
  val release: Release = Release.SNAPSHOT
) {
  override fun toString() = "$major.$minor.$patch${release.suffix}"

  enum class Release(val suffix: String) {
    SNAPSHOT("-SNAPSHOT"),
    FINAL("")
  }
}
