plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
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