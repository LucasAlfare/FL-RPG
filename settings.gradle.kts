plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "FL-RPG"

include(
  ":client",
  ":server",
  ":communication-protocol",
  ":game-domain",
)