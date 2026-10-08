plugins {
  kotlin("jvm") version "2.3.0"
}

group = "com.lucasalfare"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven { url = uri("https://jitpack.io") }
}

// Defina uma variável com a versão estável do Ktor utilizada (ex: 3.0.3)
val ktorVersion = "3.6.0"

dependencies {
  implementation(project(":communication-protocol"))

  implementation("com.github.LucasAlfare:FL-Turns:1.0.0")
  implementation("com.github.LucasAlfare:FL-Combat:1.0.0")
  implementation("com.github.LucasAlfare:FL-World:1.0.0")
  implementation("com.github.LucasAlfare:FL-Economy:1.2.0")

  // Dependências do Servidor
  implementation("io.ktor:ktor-server-netty:$ktorVersion")
  implementation("io.ktor:ktor-server-websockets:$ktorVersion")

  // Dependências de Teste (Cliente WebSocket para testar o servidor)
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