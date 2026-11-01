plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

dependencies {
  implementation(project(":game-domain"))
  implementation(libs.kotlinx.serialization.cbor)
  implementation(libs.zstd.jni)
  implementation(libs.ktoml.core)
  implementation(libs.ktoml.file)
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("com.lucasalfare.flrpg.contentcompiler.ContentPackCompilerKt")
}