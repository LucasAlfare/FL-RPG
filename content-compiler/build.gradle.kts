plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

dependencies {
  implementation(project(":game-domain"))
  implementation(libs.kotlinx.serialization.cbor)
  implementation(libs.zstd.jni)
  testImplementation(kotlin("test"))
}

application {
  // mainClass.set("com.lucasalfare.flrpg.contentcompiler.TODO?")
}