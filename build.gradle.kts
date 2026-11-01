plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.jetbrains.compose) apply false
}

val developmentEnvironment = file("dev.env").takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
    val entry = line.trim()

    if (entry.isEmpty() || entry.startsWith("#")) {
      null
    } else {
      val separator = entry.indexOf('=')

      if (separator <= 0) {
        null
      } else {
        val key = entry.substring(0, separator).trim()
        val value = entry.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")

        key to value
      }
    }
  }?.toMap().orEmpty()

subprojects {
  group = "com.lucasalfare"
  version = "1.0-SNAPSHOT"

  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }

  tasks.withType<JavaExec>().configureEach {
    developmentEnvironment.forEach { (name, value) ->
      environment(name, System.getenv(name) ?: value)
    }
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(25)
    }

    // prints all failure stats reduced at "once" after runngin tests, easy to share then to LLMs
    tasks.withType<Test>().configureEach {
      addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
        override fun beforeTest(testDescriptor: TestDescriptor) = Unit
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
          if (result.resultType == TestResult.ResultType.FAILURE) {
            println(
              """
                    ========================
                    Teste: ${testDescriptor.name}
                    Causa: ${result.exceptions.joinToString(" | ") { it.message ?: it.javaClass.simpleName }}
                    ========================
                    """.trimIndent()
            )
          }
        }
      })
    }

    dependencies {
      "testImplementation"(kotlin("test"))
    }
  }
}