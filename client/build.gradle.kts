plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.jetbrains.compose)
  alias(libs.plugins.kotlin.compose)
}

dependencies {
  implementation(project(":communication-protocol"))
  implementation(compose.desktop.currentOs)
  implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")

  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.websockets)
}

compose.desktop {
  application {
    mainClass = "com.lucasalfare.flrpg.client.ClientApplicationKt"
  }
}