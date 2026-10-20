package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.*
import java.util.*

suspend fun distributeAudiences(
  audiences: List<GameFactAudience>, registry: ConnectionRegistry, excludeCharacterId: CharacterId? = null
) {
  for (audience in audiences) {
    val fact = audience.fact
    val protocolFact = ProtocolFact(
      name = fact.name, arguments = fact.arguments.mapValues { (_, value) -> value?.toString() ?: "" })

    for (interestedId in audience.characterIds) {
      if (interestedId == excludeCharacterId) {
        continue
      }

      val targetConnection = registry.findByCharacterId(interestedId) ?: continue

      val targetSessionId = targetConnection.sessionId ?: continue

      val eventMessage = ServerEvent(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = targetSessionId,
          sequence = Sequence.ZERO
        ), type = protocolFact.name, payload = protocolFact.arguments
      )

      targetConnection.send(eventMessage)
    }
  }
}

suspend fun distributeFacts(
  facts: List<GameFact>,
  registry: ConnectionRegistry,
  actionProcessor: GameActionProcessor,
  excludeCharacterId: CharacterId? = null
) {
  val audiences = actionProcessor.findFactAudiences(facts)

  distributeAudiences(
    audiences = audiences, registry = registry, excludeCharacterId = excludeCharacterId
  )
}

suspend fun handleGameActionExecution(
  execution: GameActionExecution,
  connection: Connection,
  registry: ConnectionRegistry,
  actionProcessor: GameActionProcessor,
  offerRegistry: SessionOfferRegistry,
  presentationPreparer: InteractionPresentationPreparer,
  correlationRegistry: OperationCorrelationRegistry,
  originatingMessageId: MessageId,
  sessionId: SessionId,
  characterId: CharacterId,
  correlationId: CorrelationId,
  gameActionName: String? = null
): ServerMessage {
  return when (val result = execution.result) {
    is GameActionResult.Success -> {
      if (gameActionName == "cancel_activity") {
        val activityIdStr = result.facts.firstNotNullOfOrNull { it.arguments["activityId"]?.toString() }
          ?: execution.factAudiences.firstNotNullOfOrNull { audience -> audience.fact.arguments["activityId"]?.toString() }
        if (activityIdStr != null) {
          correlationRegistry.removeByActivityId(ActivityId(activityIdStr))
        }
      }

      val protocolFacts = result.facts.map { fact ->
        ProtocolFact(
          name = fact.name, arguments = fact.arguments.mapValues { (_, value) -> value?.toString() ?: "" })
      }
      ServerCompleted(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = sessionId,
          sequence = Sequence.ZERO,
          correlationId = correlationId
        ), facts = protocolFacts
      ).also {
        distributeAudiences(
          audiences = execution.factAudiences, registry = registry, excludeCharacterId = characterId
        )
        presentAvailableInteractions(
          connection = connection,
          actionProcessor = actionProcessor,
          offerRegistry = offerRegistry,
          preparer = presentationPreparer
        )
      }
    }

    is GameActionResult.ActivityStarted -> {
      correlationRegistry.register(originatingMessageId, result.activityId, sessionId)
      distributeAudiences(
        audiences = execution.factAudiences, registry = registry, excludeCharacterId = characterId
      )
      presentAvailableInteractions(
        connection = connection,
        actionProcessor = actionProcessor,
        offerRegistry = offerRegistry,
        preparer = presentationPreparer
      )
      ServerAccepted(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = sessionId,
          sequence = Sequence.ZERO,
          correlationId = correlationId
        ), detail = "Activity started successfully."
      )
    }

    is GameActionResult.Rejected -> {
      ServerRejected(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = sessionId,
          sequence = Sequence.ZERO,
          correlationId = correlationId
        ), reason = result.reason
      ).also {
        presentAvailableInteractions(
          connection = connection,
          actionProcessor = actionProcessor,
          offerRegistry = offerRegistry,
          preparer = presentationPreparer
        )
      }
    }
  }
}