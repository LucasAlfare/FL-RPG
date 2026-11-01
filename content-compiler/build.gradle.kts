plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

dependencies {
  implementation(project(":game-domain")) //needed? ok...
  testImplementation(kotlin("test"))
}

application {
  // mainClass.set("com.lucasalfare.flrpg.contentcompiler.TODO?") // main class needed also? ok...
}