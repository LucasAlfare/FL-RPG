plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(libs.fl.turns)
  api(libs.fl.combat)
  api(libs.fl.world)
  api(libs.fl.economy)

  implementation(libs.kotlinx.coroutines.core)
}