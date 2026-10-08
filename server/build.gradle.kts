plugins {
  kotlin("jvm") version "2.3.0"
}

group = "com.lucasalfare"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

val ktorVersion = "3.6.0"

dependencies {
  implementation(project(":communication-protocol"))
  implementation(project(":game-domain"))

  implementation("io.ktor:ktor-server-netty:$ktorVersion")
  implementation("io.ktor:ktor-server-websockets:$ktorVersion")

  testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
  testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(25)
}

tasks.test {
  useJUnitPlatform()
}