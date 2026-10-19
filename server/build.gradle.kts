plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

dependencies {
  implementation(project(":communication-protocol"))
  implementation(project(":game-domain"))

  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.websockets)

  testImplementation(libs.ktor.client.websockets)
  testImplementation(libs.ktor.server.test.host)
}

application {
  mainClass.set("com.lucasalfare.flrpg.server.MainServerKt")
}