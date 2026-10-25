plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(21)

  jvm()

  js {
    browser()
    nodejs()
  }
}

dependencies {
  commonMainImplementation(libs.kotlinx.serialization.json)
  commonMainImplementation(libs.kotlinx.coroutines.core)
}