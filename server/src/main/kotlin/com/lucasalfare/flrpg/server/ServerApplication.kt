package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.SessionLifecycleHandler
import com.lucasalfare.flrpg.contentcompiler.*
import com.lucasalfare.flrpg.gamedomain.GameActionProcessor
import com.lucasalfare.flrpg.gamedomain.NucleusId
import com.lucasalfare.flrpg.gamedomain.RealityNucleusId
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

private const val SIMULATION_TICK_INTERVAL_MS: Long = 1_000 * 1L
private const val SIMULATION_TICKS_PER_INTERVAL: Long = 1L

private const val ENV_CONTENT_PACK_PATH = "FL_RPG_CONTENT_PACK_PATH"
private const val ENV_CONTENT_KEY_ID = "FL_RPG_CONTENT_KEY_ID"
private const val ENV_CONTENT_KEY = "FL_RPG_CONTENT_KEY"

private val DEFAULT_APPLICATION_CONTENT_SUPPORT = ApplicationContentSupport(
  containerVersions = setOf(1), schemaVersions = setOf(1), sections = listOf(
    SupportedSection(id = "world", versions = setOf(1)), SupportedSection(id = "exploration", versions = setOf(1))
  ), features = emptyList()
)

fun main() {
  embeddedServer(
    Netty, port = 8080, module = Application::module
  ).start(wait = true)
}

fun Application.module(
  connectionRegistry: ConnectionRegistry = ConnectionRegistry(),
  sessionLifecycleHandler: SessionLifecycleHandler = SessionLifecycleHandler(),
  actionProcessor: GameActionProcessor? = null,
  offerRegistry: SessionOfferRegistry = SessionOfferRegistry(),
  presentationPreparer: InteractionPresentationPreparer = InteractionPresentationPreparer(
    preferredShortcutsByInteractionId = DefaultPreferredInteractionShortcuts
  ),
  pendingRegistry: SessionPendingRegistry = SessionPendingRegistry(),
  correlationRegistry: OperationCorrelationRegistry = OperationCorrelationRegistry(),
  interactionOperationRegistry: SessionInteractionOperationRegistry = SessionInteractionOperationRegistry()
) {
  val activeActionProcessor = actionProcessor ?: createDefaultActionProcessor()

  install(WebSockets)

  launch {
    // TODO: abstract this game loop to a better and sofitifed loop
    while (isActive) {
      delay(SIMULATION_TICK_INTERVAL_MS)
      advanceTimeAndDistributeAndRefreshContext(
        ticks = SIMULATION_TICKS_PER_INTERVAL,
        registry = connectionRegistry,
        actionProcessor = activeActionProcessor,
        offerRegistry = offerRegistry,
        presentationPreparer = presentationPreparer,
        correlationRegistry = correlationRegistry,
        pendingRegistry = pendingRegistry
      )
    }
  }

  routing {
    get("/health") {
      call.respondText("OK")
    }

    webSocket("/game") {
      val connection = KtorConnection(session = this)

      handleConnection(
        connection = connection,
        registry = connectionRegistry,
        sessionLifecycleHandler = sessionLifecycleHandler,
        actionProcessor = activeActionProcessor,
        offerRegistry = offerRegistry,
        presentationPreparer = presentationPreparer,
        pendingRegistry = pendingRegistry,
        correlationRegistry = correlationRegistry,
        interactionOperationRegistry = interactionOperationRegistry
      )
    }
  }
}

private fun createDefaultActionProcessor(): GameActionProcessor {
  val packPath = requireEnv(ENV_CONTENT_PACK_PATH)
  val keyId = requireEnv(ENV_CONTENT_KEY_ID)
  val keyBase64 = requireEnv(ENV_CONTENT_KEY)

  val keyBytes = decodeContentKey(keyBase64)
  val cryptoConfig = CryptoConfig(keyId = keyId, key = keyBytes)

  val resolvedPath = Path.of(packPath)
  if (!Files.isRegularFile(resolvedPath)) {
    throw IllegalStateException(
      "Content pack file not found or is not a regular file: $packPath"
    )
  }

  val containerBytes = try {
    Files.readAllBytes(resolvedPath)
  } catch (e: Exception) {
    throw IllegalStateException("Failed to read content pack at $packPath: ${e.message}", e)
  }

  val pack = try {
    ContentPackReader.read(
      container = containerBytes, cryptoConfig = cryptoConfig, support = DEFAULT_APPLICATION_CONTENT_SUPPORT
    )
  } catch (e: ContentPackException) {
    throw IllegalStateException("Failed to load content pack: ${e.error.message}", e)
  }

  val nucleus = ContentNucleusFactory.create(
    pack = pack, nucleusId = NucleusId("main_nucleus"), realityNucleusId = RealityNucleusId("main_reality")
  )

  return GameActionProcessor(nucleus)
}

private fun requireEnv(name: String): String {
  val value = System.getenv(name)?.trim()
  if (value.isNullOrEmpty()) {
    throw IllegalStateException("Required environment variable $name is missing or blank.")
  }
  return value
}

private fun decodeContentKey(base64: String): ByteArray {
  val decoded = try {
    Base64.getDecoder().decode(base64)
  } catch (e: IllegalArgumentException) {
    throw IllegalStateException(
      "Environment variable $ENV_CONTENT_KEY is not valid Base64.", e
    )
  }
  if (decoded.size != 32) {
    throw IllegalStateException(
      "Environment variable $ENV_CONTENT_KEY must decode to exactly 32 bytes (AES-256 key)."
    )
  }
  return decoded
}