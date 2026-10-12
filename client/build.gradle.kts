plugins {
  kotlin("jvm") version "2.3.0"
  id("org.jetbrains.compose") version "1.10.3"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "com.lucasalfare"
version = "1.0-SNAPSHOT"

repositories {
  google()
  mavenCentral()
}

dependencies {
  implementation(project(":communication-protocol"))
  implementation(compose.desktop.currentOs)
  implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")

  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(25)
}

tasks.test {
  useJUnitPlatform()
}

compose.desktop {
  application {
    mainClass = "com.lucasalfare.flrpg.client.MainKt"
  }
}
