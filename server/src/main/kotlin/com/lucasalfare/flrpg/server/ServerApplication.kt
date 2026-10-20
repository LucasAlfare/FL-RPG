package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.SessionLifecycleHandler
import com.lucasalfare.flrpg.gamedomain.GameActionProcessor
import com.lucasalfare.flrpg.gamedomain.NucleusId
import com.lucasalfare.flrpg.gamedomain.buildDevelopmentNucleus
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SIMULATION_TICK_INTERVAL_MS: Long = 1_000 * 1L
private const val SIMULATION_TICKS_PER_INTERVAL: Long = 1L

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
  val activeActionProcessor = actionProcessor ?: GameActionProcessor(
    buildDevelopmentNucleus(NucleusId("main_nucleus"))
  )

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