plugins {
  kotlin("jvm") version "2.3.0"
}

group = "com.lucasalfare"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven { url = uri("https://jitpack.io") }
}

dependencies {
  implementation("com.github.LucasAlfare:FL-Turns:1.0.0")
  implementation("com.github.LucasAlfare:FL-Combat:1.0.0")
  implementation("com.github.LucasAlfare:FL-World:1.0.0")
  implementation("com.github.LucasAlfare:FL-Economy:1.2.0")

  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(25)
}

tasks.test {
  useJUnitPlatform()
}