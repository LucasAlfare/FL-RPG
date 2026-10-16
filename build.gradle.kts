plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.jetbrains.compose) apply false
}

subprojects {
  group = "com.lucasalfare"
  version = "1.0-SNAPSHOT"

  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(25)
    }

    tasks.withType<Test> {
      useJUnitPlatform()
    }

    dependencies {
      "testImplementation"(kotlin("test"))
    }
  }
}