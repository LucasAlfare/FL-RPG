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

tasks.register<JavaExec>("compileDevelopmentContentPack") {
  group = "content"
  description = "Compile the development content pack from private TOML sources into an encrypted .flcp artifact."

  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.lucasalfare.flrpg.contentcompiler.ContentPackCompilerKt")

  val contentSourceDir = project.findProperty("contentSourceDir") as String?
    ?: throw GradleException("Missing required property -PcontentSourceDir pointing to the private content source directory.")

  val contentPackOutput = project.findProperty("contentPackOutput") as String?
    ?: rootProject.layout.projectDirectory.dir("content/packs").file("development.flcp").asFile.absolutePath

  args = listOf(contentSourceDir, contentPackOutput)

  // Forward crypto env vars if present; never print or log their values.
  environment(
    "FL_RPG_CONTENT_KEY_ID",
    System.getenv("FL_RPG_CONTENT_KEY_ID") ?: ""
  )
  environment(
    "FL_RPG_CONTENT_KEY",
    System.getenv("FL_RPG_CONTENT_KEY") ?: ""
  )

  doFirst {
    if (System.getenv("FL_RPG_CONTENT_KEY_ID").isNullOrBlank()) {
      throw GradleException("Environment variable FL_RPG_CONTENT_KEY_ID is required and must not be blank.")
    }
    if (System.getenv("FL_RPG_CONTENT_KEY").isNullOrBlank()) {
      throw GradleException("Environment variable FL_RPG_CONTENT_KEY is required and must not be blank.")
    }
  }
}