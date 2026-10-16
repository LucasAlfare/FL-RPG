package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.*
import com.lucasalfare.flworld.WorldLocationId
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.test.*

private class InMemoryConnection(
  override val id: String = UUID.randomUUID().toString(),
  override var sessionId: SessionId? = null,
  override var characterId: CharacterId? = null
) : Connection {
  private val incoming = mutableListOf<ClientMessage>()
  val sentMessages = mutableListOf<ServerMessage>()
  var closed = false
    private set

  fun enqueue(vararg messages: ClientMessage) {
    incoming.addAll(messages)
  }

  override suspend fun send(message: ServerMessage) {
    sentMessages.add(message)
  }

  override suspend fun receive(): ClientMessage? = if (incoming.isEmpty()) null else incoming.removeAt(0)

  override suspend fun close() {
    closed = true
  }
}

private fun clientHello(
  messageId: String,
  sessionId: SessionId,
  sequence: Sequence,
  version: ProtocolVersion = ProtocolVersion.V1,
  resumeSessionId: SessionId? = null
): ClientHello = ClientHello(
  metadata = MessageMetadata(
    messageId = MessageId(messageId), sessionId = sessionId, sequence = sequence, version = version
  ), resumeSessionId = resumeSessionId
)

private fun clientAction(
  messageId: String, sessionId: SessionId, sequence: Sequence, actionId: String = "test_action"
): ClientAction = ClientAction(
  metadata = MessageMetadata(
    messageId = MessageId(messageId), sessionId = sessionId, sequence = sequence
  ), actionId = actionId
)

private fun clientSelection(
  messageId: String, sessionId: SessionId, sequence: Sequence, choiceId: String
): ClientSelection = ClientSelection(
  metadata = MessageMetadata(
    messageId = MessageId(messageId), sessionId = sessionId, sequence = sequence
  ), choiceId = choiceId
)

private fun clientText(
  messageId: String, sessionId: SessionId, sequence: Sequence, text: String
): ClientTextCommand = ClientTextCommand(
  metadata = MessageMetadata(
    messageId = MessageId(messageId), sessionId = sessionId, sequence = sequence
  ), rawText = text
)

private fun clientCancel(
  messageId: String, sessionId: SessionId, sequence: Sequence, targetId: String
): ClientCancel = ClientCancel(
  metadata = MessageMetadata(
    messageId = MessageId(messageId), sessionId = sessionId, sequence = sequence
  ), targetMessageId = MessageId(targetId)
)

private fun buildProcessor(): GameActionProcessor {
  val nucleus = Nucleus(NucleusId("test_nucleus"))
  nucleus.registerLocation("spawn")
  nucleus.registerLocation("forest")
  nucleus.connectLocations("spawn", "forest")
  return GameActionProcessor(nucleus)
}

private fun buildE2ENucleus(): Nucleus {
  val nucleus = Nucleus(NucleusId("e2e_nucleus"))
  nucleus.registerLocation("spawn")
  nucleus.registerLocation("forest")
  nucleus.connectLocations("spawn", "forest")
  return nucleus
}

private fun PresentationSnapshot.hasChoice(choiceId: String): Boolean = document.blocks.any { block ->
  (block.content as? BlockContent.Choices)?.choices?.any { it.id == choiceId } == true
}

private suspend fun DefaultClientWebSocketSession.receiveServerMessage(): ServerMessage {
  val frame = incoming.receive() as Frame.Text
  return frame.readText().toProtocolMessage<ProtocolMessage>() as ServerMessage
}

private suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.receiveIgnoringWorldEvents(): T {
  while (true) {
    when (val message = receiveServerMessage()) {
      is T -> return message
      is ServerEvent -> continue
      else -> fail(
        "Expected ${T::class.simpleName} but received ${message::class.simpleName} " + "while waiting; only ServerEvent may be interleaved."
      )
    }
  }
}

class ServerTests {

  @Test
  fun connectionRegistry_registersFindsAndUnregistersConnections() {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")

    registry.register(connection)

    assertSame(connection, registry.findById("conn-1"))
    assertEquals(listOf(connection), registry.activeConnections())

    registry.unregister("conn-1")

    assertNull(registry.findById("conn-1"))
    assertTrue(registry.activeConnections().isEmpty())
  }

  @Test
  fun connectionRegistry_bindsAndUnbindsCharacters() {
    val registry = ConnectionRegistry()
    val sessionId = SessionId("session-1")
    val characterId = CharacterId("character-1")
    val connection = InMemoryConnection(id = "conn-1", sessionId = sessionId)

    registry.register(connection)

    assertTrue(registry.bindCharacter(sessionId, characterId))
    assertEquals(characterId, registry.findCharacterIdBySessionId(sessionId))
    assertSame(connection, registry.findByCharacterId(characterId))
    assertSame(connection, registry.findBySessionId(sessionId))

    registry.unbindCharacter(sessionId)

    assertNull(registry.findCharacterIdBySessionId(sessionId))
    assertNull(registry.findByCharacterId(characterId))
    assertSame(connection, registry.findBySessionId(sessionId))

    assertFalse(registry.bindCharacter(SessionId("unknown"), characterId))
  }

  @Test
  fun sessionPendingRegistry_keepsSessionsIndependent() {
    val registry = SessionPendingRegistry()
    val sessionA = SessionId("session-a")
    val sessionB = SessionId("session-b")

    val interactionA = Interaction(
      id = InteractionId("enter_world"), label = "Enter the world", actionName = "enter_world"
    )
    val interactionB = Interaction(
      id = InteractionId("move"),
      label = "Move",
      actionName = "move",
      requiredArgumentNames = listOf("targetLocationId")
    )

    val pendingA = PendingInteraction(interaction = interactionA)
    val pendingB = PendingInteraction(interaction = interactionB)

    registry.set(sessionA, pendingA)
    registry.set(sessionB, pendingB)

    assertSame(pendingA, registry.find(sessionA))
    assertSame(pendingB, registry.find(sessionB))
    assertEquals(listOf("targetLocationId"), pendingB.remainingArguments)
    assertTrue(pendingA.remainingArguments.isEmpty())
    assertTrue(pendingA.collectedArguments.isEmpty())

    pendingB.collectedArguments["targetLocationId"] = "forest"
    pendingB.remainingArguments.clear()

    assertTrue(pendingA.collectedArguments.isEmpty())
    assertTrue(pendingA.remainingArguments.isEmpty())
    assertSame(pendingA, registry.find(sessionA))
    assertSame(pendingB, registry.find(sessionB))

    registry.remove(sessionA)

    assertNull(registry.find(sessionA))
    assertSame(pendingB, registry.find(sessionB))
  }

  @Test
  fun sessionOfferRegistry_replacesAndClearsOffers() {
    val registry = SessionOfferRegistry()
    val sessionId = SessionId("session-1")

    val state = registry.getOrCreate(sessionId)

    assertSame(state, registry.getOrCreate(sessionId))
    assertEquals(Revision.ZERO, state.revision)
    assertTrue(state.offeredSnapshot().isEmpty())

    val interactionA = Interaction(id = InteractionId("a"), label = "A")
    val interactionB = Interaction(id = InteractionId("b"), label = "B")

    state.replaceOffers(listOf(OfferedOption("opt_a", interactionA)), Revision(1L))

    assertEquals(Revision(1L), state.revision)
    assertSame(interactionA, state.findInteraction("opt_a"))
    assertNull(state.findInteraction("opt_b"))

    state.replaceOffers(listOf(OfferedOption("opt_b", interactionB)), Revision(2L))

    assertEquals(Revision(2L), state.revision)
    assertNull(state.findInteraction("opt_a"))
    assertSame(interactionB, state.findInteraction("opt_b"))

    registry.remove(sessionId)

    assertNull(registry.find(sessionId))
  }

  @Test
  fun sequentialShortcutAssigner_generatesUniqueShortcuts() {
    val assigner = SequentialLetterShortcutAssigner()

    val choices = listOf(
      Choice(id = "a", text = "Alpha"), Choice(id = "b", text = "Bravo"), Choice(id = "c", text = "Charlie")
    )

    val assigned = assigner.assign(choices)

    assertEquals(listOf("A", "B", "C"), assigned.map { it.shortcut })
    assertEquals(3, assigned.mapNotNull { it.shortcut }.distinct().size)

    val mixed = listOf(
      Choice(id = "a", text = "Alpha", shortcut = "X"),
      Choice(id = "b", text = "Bravo"),
      Choice(id = "c", text = "Charlie")
    )

    val assignedMixed = assigner.assign(mixed)

    assertEquals("X", assignedMixed[0].shortcut)
    assertEquals(3, assignedMixed.mapNotNull { it.shortcut }.distinct().size)
  }

  @Test
  fun sequentialShortcutAssigner_rejectsConflictingEnabledShortcuts() {
    val assigner = SequentialLetterShortcutAssigner()

    val conflicting = listOf(
      Choice(id = "a", text = "Alpha", shortcut = "X", enabled = true),
      Choice(id = "b", text = "Bravo", shortcut = "X", enabled = true)
    )

    assertFailsWith<IllegalArgumentException> {
      assigner.assign(conflicting)
    }

    val nonConflicting = listOf(
      Choice(id = "a", text = "Alpha", shortcut = "X", enabled = true),
      Choice(id = "b", text = "Bravo", shortcut = "X", enabled = false)
    )

    val assigned = assigner.assign(nonConflicting)

    assertEquals(2, assigned.size)
    assertEquals("X", assigned[0].shortcut)
    assertEquals("X", assigned[1].shortcut)
  }

  @Test
  fun interactionPresentationPreparer_buildsChoicesWithCorrectIds() {
    val preparer = InteractionPresentationPreparer()

    val interactions = listOf(
      Interaction(
        id = InteractionId("enter_world"), label = "Enter the world", actionName = "enter_world"
      ), Interaction(
        id = InteractionId("move"),
        label = "Move",
        actionName = "move",
        requiredArgumentNames = listOf("targetLocationId")
      )
    )

    val prepared = preparer.prepare(interactions, baseRevision = Revision.ZERO)

    assertEquals(2, prepared.choices.size)
    assertEquals(listOf("opt_enter_world", "opt_move"), prepared.choices.map { it.id })
    assertEquals(listOf("Enter the world", "Move"), prepared.choices.map { it.text })
    assertEquals(listOf("opt_enter_world", "opt_move"), prepared.offered.map { it.choiceId })
    assertSame(interactions[0], prepared.offered[0].interaction)
    assertSame(interactions[1], prepared.offered[1].interaction)

    assertEquals(2, prepared.choices.mapNotNull { it.shortcut }.distinct().size)
    assertTrue(prepared.choices.all { it.enabled })

    assertEquals(Revision(1L), prepared.document.revision)
  }

  @Test
  fun handleConnection_establishesSessionOnValidHello() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    connection.enqueue(clientHello("hello-1", sessionId, Sequence.ZERO))

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    val welcome = connection.sentMessages.single()
    assertTrue(welcome is ServerWelcome)
    assertEquals(sessionId, welcome.metadata.sessionId)
    assertFalse(welcome.isResumed)
    assertTrue(connection.closed)
  }

  @Test
  fun handleConnection_rejectsInvalidHello() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO, version = ProtocolVersion(2, 0))
    )

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    val response = connection.sentMessages.single()
    assertTrue(response is ServerReject)
    assertEquals(RejectionReason.INCOMPATIBLE_VERSION, response.reason)
    assertNull(registry.findById("conn-1"))
  }

  @Test
  fun handleConnection_rejectsRepeatedHelloAfterSessionEstablished() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO), clientHello("hello-2", sessionId, Sequence(1))
    )

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    assertTrue(connection.sentMessages.single() is ServerWelcome)
  }

  @Test
  fun handleConnection_ignoresMessagesWithWrongSessionId() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    val wrongSessionId = SessionId("session-other")
    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO), clientAction("action-1", wrongSessionId, Sequence.ZERO)
    )

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    assertTrue(connection.sentMessages.single() is ServerWelcome)
  }

  @Test
  fun handleConnection_cleansStateAfterDisconnect() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val pendingRegistry = SessionPendingRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    val interaction = Interaction(
      id = InteractionId("enter_world"), label = "Enter the world", actionName = "enter_world"
    )
    offerRegistry.getOrCreate(sessionId).replaceOffers(
      listOf(OfferedOption("opt_enter_world", interaction)), Revision(1L)
    )
    pendingRegistry.set(sessionId, PendingInteraction(interaction = interaction))

    connection.enqueue(clientHello("hello-1", sessionId, Sequence.ZERO))

    handleConnection(
      connection = connection, registry = registry, offerRegistry = offerRegistry, pendingRegistry = pendingRegistry
    )

    assertTrue(connection.closed)
    assertNull(registry.findById("conn-1"))
    assertTrue(registry.activeConnections().isEmpty())
    assertNull(offerRegistry.find(sessionId))
    assertNull(pendingRegistry.find(sessionId))
    assertNull(connection.sessionId)
    assertNull(connection.characterId)
  }

  @Test
  fun handleConnection_doesNotConfuseConnectionIdAndSessionId() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "connection-xyz")
    val sessionId = SessionId("session-abc")
    connection.enqueue(clientHello("hello-1", sessionId, Sequence.ZERO))

    handleConnection(connection = connection, registry = registry)

    val welcome = connection.sentMessages.single() as ServerWelcome
    assertEquals(sessionId, welcome.metadata.sessionId)
    assertNotEquals(SessionId(connection.id), welcome.metadata.sessionId)
    assertEquals("connection-xyz", connection.id)
  }

  @Test
  fun handleConnection_ignoresDuplicateMessages() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    val duplicateMessageId = MessageId("action-dup")
    val firstAction = ClientAction(
      metadata = MessageMetadata(
        messageId = duplicateMessageId, sessionId = sessionId, sequence = Sequence.ZERO
      ), actionId = "test_action"
    )
    val duplicateAction = ClientAction(
      metadata = MessageMetadata(
        messageId = duplicateMessageId, sessionId = sessionId, sequence = Sequence(1)
      ), actionId = "test_action"
    )
    val followUpAction = clientAction("action-2", sessionId, Sequence(1))

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO), firstAction, duplicateAction, followUpAction
    )

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    assertTrue(connection.sentMessages.single() is ServerWelcome)
  }

  @Test
  fun handleConnection_ignoresOutOfOrderMessages() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    val staleAction = clientAction("action-stale", sessionId, Sequence(1))
    val outOfOrderAction = clientAction("action-forward", sessionId, Sequence(5))
    val expectedAction = clientAction("action-expected", sessionId, Sequence.ZERO)

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO), staleAction, outOfOrderAction, expectedAction
    )

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    assertTrue(connection.sentMessages.single() is ServerWelcome)
  }

  @Test
  fun handleConnection_doesNotExecuteActionAfterSequenceGap() = runBlocking {
    val registry = ConnectionRegistry()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")
    val gapAction = clientAction("action-gap", sessionId, Sequence(7))
    val followingAction = clientAction("action-next", sessionId, Sequence.ZERO)

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO), gapAction, followingAction
    )

    handleConnection(connection = connection, registry = registry)

    assertEquals(1, connection.sentMessages.size)
    assertTrue(connection.sentMessages.single() is ServerWelcome)
    assertTrue(connection.closed)
  }

  @Test
  fun handleConnection_rejectsUnknownSelection() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_does_not_exist")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val rejections = connection.sentMessages.filterIsInstance<ServerRejected>()
    assertEquals(1, rejections.size)
    assertTrue(rejections.single().reason.contains("Unknown or expired choice"))
    assertTrue(connection.sentMessages.none { it is ServerCompleted })
  }

  @Test
  fun handleConnection_rejectsExpiredSelection() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_enter_world"),
      clientSelection("sel-2", sessionId, Sequence(1), "opt_enter_world")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val completed = connection.sentMessages.filterIsInstance<ServerCompleted>()
    assertEquals(1, completed.size)

    val rejections = connection.sentMessages.filterIsInstance<ServerRejected>()
    assertEquals(1, rejections.size)
    assertTrue(rejections.single().reason.contains("Unknown or expired choice"))
  }

  @Test
  fun handleConnection_collectsRequiredInteractionArguments() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_enter_world"),
      clientSelection("sel-2", sessionId, Sequence(1), "opt_move")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val accepted = connection.sentMessages.filterIsInstance<ServerAccepted>()
    assertEquals(1, accepted.size)
    assertTrue(accepted.single().detail?.contains("targetLocationId") == true)

    val snapshots = connection.sentMessages.filterIsInstance<PresentationSnapshot>()
    val promptSnapshot = snapshots.last()
    val hasPrompt = promptSnapshot.document.blocks.any { block ->
      (block.content as? BlockContent.Text)?.text?.contains("targetLocationId") == true
    }
    assertTrue(hasPrompt)

    assertTrue(connection.sentMessages.filterIsInstance<ServerCompleted>().flatMap { it.facts }
      .none { it.name == "character_moved" })
  }

  @Test
  fun handleConnection_keepsPendingInteractionsIsolatedBySession() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val pendingRegistry = SessionPendingRegistry()
    val actionProcessor = buildProcessor()

    val connectionA = InMemoryConnection(id = "conn-a")
    val connectionB = InMemoryConnection(id = "conn-b")
    val sessionA = SessionId("session-a")
    val sessionB = SessionId("session-b")

    connectionA.enqueue(
      clientHello("hello-a", sessionA, Sequence.ZERO),
      clientSelection("sel-a1", sessionA, Sequence.ZERO, "opt_enter_world"),
      clientSelection("sel-a2", sessionA, Sequence(1), "opt_move")
    )
    connectionB.enqueue(
      clientHello("hello-b", sessionB, Sequence.ZERO),
      clientSelection("sel-b1", sessionB, Sequence.ZERO, "opt_enter_world")
    )

    handleConnection(
      connection = connectionA,
      registry = registry,
      actionProcessor = actionProcessor,
      offerRegistry = offerRegistry,
      pendingRegistry = pendingRegistry
    )

    handleConnection(
      connection = connectionB,
      registry = registry,
      actionProcessor = actionProcessor,
      offerRegistry = offerRegistry,
      pendingRegistry = pendingRegistry
    )

    val acceptedA = connectionA.sentMessages.filterIsInstance<ServerAccepted>()
    val acceptedB = connectionB.sentMessages.filterIsInstance<ServerAccepted>()

    assertEquals(1, acceptedA.size)
    assertTrue(acceptedA.single().detail?.contains("targetLocationId") == true)
    assertTrue(acceptedB.isEmpty())

    val completedA = connectionA.sentMessages.filterIsInstance<ServerCompleted>()
    val completedB = connectionB.sentMessages.filterIsInstance<ServerCompleted>()
    assertEquals(1, completedA.size)
    assertEquals(1, completedB.size)
    assertTrue(completedB.single().facts.any { it.name == "character_entered" })
  }

  @Test
  fun handleConnection_clearsCompletedPendingInteraction() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_enter_world"),
      clientSelection("sel-2", sessionId, Sequence(1), "opt_move"),
      clientText("txt-1", sessionId, Sequence(2), "forest"),
      clientText("txt-2", sessionId, Sequence(3), "noop")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val moveCompleted = connection.sentMessages.filterIsInstance<ServerCompleted>().flatMap { it.facts }
      .any { it.name == "character_moved" }
    assertTrue(moveCompleted)

    val finalRejection = connection.sentMessages.filterIsInstance<ServerRejected>().last()
    assertTrue(finalRejection.reason.contains("No pending interaction"))
  }

  @Test
  fun handleConnection_clearsCancelledPendingInteraction() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_enter_world"),
      clientSelection("sel-2", sessionId, Sequence(1), "opt_move"),
      clientCancel("cancel-1", sessionId, Sequence(2), "sel-2"),
      clientText("txt-1", sessionId, Sequence(3), "forest")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val cancelled = connection.sentMessages.filterIsInstance<ServerAccepted>().any {
      it.detail?.contains("Cancelled pending interaction") == true
    }
    assertTrue(cancelled)

    val finalRejection = connection.sentMessages.filterIsInstance<ServerRejected>().last()
    assertTrue(finalRejection.reason.contains("No pending interaction"))
  }

  @Test
  fun handleConnection_executesValidActionThroughDomain() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_enter_world")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val completed = connection.sentMessages.filterIsInstance<ServerCompleted>()
    assertEquals(1, completed.size)
    assertTrue(completed.single().facts.any { it.name == "character_entered" })
  }

  @Test
  fun handleConnection_doesNotExecuteInvalidAction() = runBlocking {
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val actionProcessor = buildProcessor()
    val connection = InMemoryConnection(id = "conn-1")
    val sessionId = SessionId("session-1")

    connection.enqueue(
      clientHello("hello-1", sessionId, Sequence.ZERO),
      clientSelection("sel-1", sessionId, Sequence.ZERO, "opt_enter_world"),
      clientSelection("sel-2", sessionId, Sequence(1), "opt_move"),
      clientText("txt-1", sessionId, Sequence(2), "does_not_exist")
    )

    handleConnection(
      connection = connection, registry = registry, actionProcessor = actionProcessor, offerRegistry = offerRegistry
    )

    val rejections = connection.sentMessages.filterIsInstance<ServerRejected>()
    assertTrue(rejections.any { it.reason.contains("does not exist") })

    val movedFacts = connection.sentMessages.filterIsInstance<ServerCompleted>().flatMap { it.facts }
      .filter { it.name == "character_moved" || it.name == "character_movement_completed" }
    assertTrue(movedFacts.isEmpty())
  }

  @Test
  fun distributeAudiences_sendsEventsOnlyToRelevantCharacters() = runBlocking {
    val registry = ConnectionRegistry()
    val sessionA = SessionId("session-a")
    val sessionB = SessionId("session-b")
    val characterA = CharacterId("char-a")
    val characterB = CharacterId("char-b")

    val connectionA = InMemoryConnection(id = "conn-a", sessionId = sessionA, characterId = characterA)
    val connectionB = InMemoryConnection(id = "conn-b", sessionId = sessionB, characterId = characterB)
    registry.register(connectionA)
    registry.register(connectionB)

    val fact = GameFact(name = "presence", arguments = mapOf("locationId" to "spawn"))
    val audience = GameFactAudience(fact = fact, characterIds = listOf(characterA))

    distributeAudiences(listOf(audience), registry)

    val eventsA = connectionA.sentMessages.filterIsInstance<ServerEvent>()
    val eventsB = connectionB.sentMessages.filterIsInstance<ServerEvent>()

    assertEquals(1, eventsA.size)
    assertEquals("presence", eventsA.single().type)
    assertEquals("spawn", eventsA.single().payload["locationId"])
    assertTrue(eventsB.isEmpty())
  }

  @Test
  fun distributeAudiences_doesNotDuplicateActorResult() = runBlocking {
    val registry = ConnectionRegistry()
    val sessionA = SessionId("session-a")
    val sessionB = SessionId("session-b")
    val characterA = CharacterId("char-a")
    val characterB = CharacterId("char-b")

    val connectionA = InMemoryConnection(id = "conn-a", sessionId = sessionA, characterId = characterA)
    val connectionB = InMemoryConnection(id = "conn-b", sessionId = sessionB, characterId = characterB)
    registry.register(connectionA)
    registry.register(connectionB)

    val fact = GameFact(name = "presence", arguments = mapOf("locationId" to "spawn"))
    val audience = GameFactAudience(fact = fact, characterIds = listOf(characterA, characterB))

    distributeAudiences(listOf(audience), registry, excludeCharacterId = characterA)

    assertTrue(connectionA.sentMessages.isEmpty())
    assertEquals(1, connectionB.sentMessages.filterIsInstance<ServerEvent>().size)
  }

  @Test
  fun distributeAudiences_handlesMissingRecipientConnection() = runBlocking {
    val registry = ConnectionRegistry()

    val fact = GameFact(name = "presence", arguments = mapOf("locationId" to "spawn"))
    val audience = GameFactAudience(
      fact = fact, characterIds = listOf(CharacterId("missing-character"))
    )

    distributeAudiences(listOf(audience), registry)

    assertTrue(registry.activeConnections().isEmpty())
  }
}

class ServerNetworkTests {

  @Test
  fun healthEndpoint_returnsOk() = testApplication {
    application {
      module(actionProcessor = buildProcessor())
    }

    val response = client.get("/health")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("OK", response.bodyAsText())
  }

  @Test
  fun webSocket_establishesValidSession() = testApplication {
    val registry = ConnectionRegistry()
    application {
      module(connectionRegistry = registry, actionProcessor = buildProcessor())
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("session-1")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-1", sessionId, Sequence.ZERO).toJson()))

      val welcome = receiveServerMessage()
      assertIs<ServerWelcome>(welcome)
      assertEquals(sessionId, welcome.metadata.sessionId)
      assertFalse(welcome.isResumed)
    }
  }

  @Test
  fun webSocket_rejectsInvalidHandshake() = testApplication {
    val registry = ConnectionRegistry()
    application {
      module(connectionRegistry = registry, actionProcessor = buildProcessor())
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("session-1")

    wsClient.webSocket("/game") {
      send(
        Frame.Text(
          clientHello("hello-1", sessionId, Sequence.ZERO, version = ProtocolVersion(2, 0)).toJson()
        )
      )

      val response = receiveServerMessage()
      assertIs<ServerReject>(response)
      assertEquals(RejectionReason.INCOMPATIBLE_VERSION, response.reason)
    }
  }

  @Test
  fun server_50ConcurrentWebSocketClientsCanEnterAndDisconnect() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }

    coroutineScope {
      val gate = CompletableDeferred<Unit>()
      val clients = (1..50).map { index ->
        launch {
          val sessionId = SessionId("bulk-session-$index")
          gate.await()
          wsClient.webSocket("/game") {
            send(Frame.Text(clientHello("hello-$index", sessionId, Sequence.ZERO).toJson()))
            assertIs<ServerWelcome>(receiveServerMessage())
            receiveIgnoringWorldEvents<PresentationSnapshot>()

            send(
              Frame.Text(
                clientSelection("select-$index", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
              )
            )
            receiveIgnoringWorldEvents<PresentationSnapshot>()
            val completed = receiveIgnoringWorldEvents<ServerCompleted>()
            assertTrue(completed.facts.any { it.name == "character_entered" })

            close()
          }
        }
      }
      gate.complete(Unit)
      clients.joinAll()
    }

    withTimeout(10_000) {
      while (registry.activeConnections().isNotEmpty() || nucleus.characters.isNotEmpty()) {
        delay(10)
      }
    }

    assertTrue(registry.activeConnections().isEmpty())
    assertTrue(nucleus.characters.isEmpty())
  }

  @Test
  fun server_concurrentSessionsDoNotSharePendingInteractions() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val pendingRegistry = SessionPendingRegistry()

    application {
      module(
        connectionRegistry = registry,
        actionProcessor = processor,
        offerRegistry = offerRegistry,
        pendingRegistry = pendingRegistry
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionA = SessionId("concurrent-session-a")
    val sessionB = SessionId("concurrent-session-b")

    coroutineScope {
      val gate = CompletableDeferred<Unit>()

      val clientA = launch {
        gate.await()
        wsClient.webSocket("/game") {
          send(Frame.Text(clientHello("hello-a", sessionA, Sequence.ZERO).toJson()))
          assertIs<ServerWelcome>(receiveServerMessage())
          receiveIgnoringWorldEvents<PresentationSnapshot>()

          send(Frame.Text(clientSelection("select-a-enter", sessionA, Sequence.ZERO, "opt_enter_world").toJson()))
          receiveIgnoringWorldEvents<PresentationSnapshot>()
          receiveIgnoringWorldEvents<ServerCompleted>()

          send(Frame.Text(clientSelection("select-a-move", sessionA, Sequence(1), "opt_move").toJson()))
          receiveIgnoringWorldEvents<PresentationSnapshot>()
          receiveIgnoringWorldEvents<ServerAccepted>()

          send(Frame.Text(clientText("text-a", sessionA, Sequence(2), "forest").toJson()))
          receiveIgnoringWorldEvents<PresentationSnapshot>()
          val completed = receiveIgnoringWorldEvents<ServerCompleted>()
          assertTrue(completed.facts.any { it.name == "character_moved" })

          close()
        }
      }

      val clientB = launch {
        gate.await()
        wsClient.webSocket("/game") {
          send(Frame.Text(clientHello("hello-b", sessionB, Sequence.ZERO).toJson()))
          assertIs<ServerWelcome>(receiveServerMessage())
          receiveIgnoringWorldEvents<PresentationSnapshot>()

          send(Frame.Text(clientSelection("select-b-enter", sessionB, Sequence.ZERO, "opt_enter_world").toJson()))
          receiveIgnoringWorldEvents<PresentationSnapshot>()
          receiveIgnoringWorldEvents<ServerCompleted>()

          send(Frame.Text(clientSelection("select-b-move", sessionB, Sequence(1), "opt_move").toJson()))
          receiveIgnoringWorldEvents<PresentationSnapshot>()
          receiveIgnoringWorldEvents<ServerAccepted>()

          send(Frame.Text(clientText("text-b", sessionB, Sequence(2), "forest").toJson()))
          receiveIgnoringWorldEvents<PresentationSnapshot>()
          val completed = receiveIgnoringWorldEvents<ServerCompleted>()
          assertTrue(completed.facts.any { it.name == "character_moved" })

          close()
        }
      }

      gate.complete(Unit)
      clientA.join()
      clientB.join()
    }

    withTimeout(10_000) {
      while (registry.activeConnections().isNotEmpty() || nucleus.characters.isNotEmpty()) {
        delay(10)
      }
    }

    assertNull(pendingRegistry.find(sessionA))
    assertNull(pendingRegistry.find(sessionB))
    assertTrue(nucleus.characters.isEmpty())
  }

  @Test
  fun server_burstOfValidAndInvalidSelectionsPreservesState() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("burst-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-burst", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      val invalidCount = 30
      for (index in 0 until invalidCount) {
        send(
          Frame.Text(
            clientSelection(
              "select-invalid-$index", sessionId, Sequence(index.toLong()), "opt_invalid"
            ).toJson()
          )
        )
      }

      repeat(invalidCount) {
        val response = receiveServerMessage()
        assertIs<ServerRejected>(response)
      }

      assertTrue(nucleus.characters.isEmpty())

      send(
        Frame.Text(
          clientSelection(
            "select-valid-enter", sessionId, Sequence(invalidCount.toLong()), "opt_enter_world"
          ).toJson()
        )
      )

      assertIs<PresentationSnapshot>(receiveServerMessage())
      val completed = receiveServerMessage()
      assertIs<ServerCompleted>(completed)
      assertTrue(completed.facts.any { it.name == "character_entered" })

      val characterId = registry.findCharacterIdBySessionId(sessionId)
      assertNotNull(characterId)
      assertEquals(listOf(characterId), nucleus.getCharactersAtLocation(WorldLocationId("spawn")))
      assertTrue(nucleus.world.getActiveMovements().isEmpty())
    }
  }
}

class ServerEndToEndTests {

  @Test
  fun e2e_playerEntersWorldSuccessfully() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-enter-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-enter", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
        )
      )
      assertIs<PresentationSnapshot>(receiveServerMessage())
      val completed = receiveServerMessage()
      assertIs<ServerCompleted>(completed)
      assertTrue(completed.facts.any { it.name == "character_entered" })

      val characterId = registry.findCharacterIdBySessionId(sessionId)
      assertNotNull(characterId)
      assertTrue(nucleus.containsCharacter(characterId))
      assertEquals(listOf(characterId), nucleus.getCharactersAtLocation(WorldLocationId("spawn")))
    }
  }

  @Test
  fun e2e_playerReceivesAvailableInteractionsAfterEnteringWorld() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-interactions-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-interactions", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())

      val initialSnapshot = receiveServerMessage()
      assertIs<PresentationSnapshot>(initialSnapshot)
      assertTrue(initialSnapshot.hasChoice("opt_enter_world"))
      assertFalse(initialSnapshot.hasChoice("opt_move"))

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
        )
      )
      val afterEnterSnapshot = receiveServerMessage()
      assertIs<PresentationSnapshot>(afterEnterSnapshot)
      assertTrue(afterEnterSnapshot.hasChoice("opt_move"))
      assertFalse(afterEnterSnapshot.hasChoice("opt_enter_world"))

      assertIs<ServerCompleted>(receiveServerMessage())
    }
  }

  @Test
  fun e2e_playerStartsMovementWithoutImmediateTeleport() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-move-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-move", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
        )
      )
      assertIs<PresentationSnapshot>(receiveServerMessage())
      assertIs<ServerCompleted>(receiveServerMessage())

      send(Frame.Text(clientSelection("sel-move", sessionId, Sequence(1), "opt_move").toJson()))
      assertIs<PresentationSnapshot>(receiveServerMessage())
      assertIs<ServerAccepted>(receiveServerMessage())

      send(Frame.Text(clientText("txt-target", sessionId, Sequence(2), "forest").toJson()))
      assertIs<PresentationSnapshot>(receiveServerMessage())
      val completed = receiveServerMessage()
      assertIs<ServerCompleted>(completed)
      assertTrue(completed.facts.any { it.name == "character_moved" })

      val characterId = registry.findCharacterIdBySessionId(sessionId)
      assertNotNull(characterId)
      assertEquals(listOf(characterId), nucleus.getCharactersAtLocation(WorldLocationId("spawn")))
      assertTrue(nucleus.getCharactersAtLocation(WorldLocationId("forest")).isEmpty())
    }
  }

  @Test
  fun e2e_playerReceivesMovementCompletionEvent() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-move-completion-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-move-completion", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
        )
      )
      assertIs<PresentationSnapshot>(receiveServerMessage())
      assertIs<ServerCompleted>(receiveServerMessage())

      send(Frame.Text(clientSelection("sel-move", sessionId, Sequence(1), "opt_move").toJson()))
      assertIs<PresentationSnapshot>(receiveServerMessage())
      assertIs<ServerAccepted>(receiveServerMessage())

      send(Frame.Text(clientText("txt-target", sessionId, Sequence(2), "forest").toJson()))
      assertIs<PresentationSnapshot>(receiveServerMessage())
      val moveStarted = receiveServerMessage()
      assertIs<ServerCompleted>(moveStarted)
      assertTrue(moveStarted.facts.any { it.name == "character_moved" })

      val characterId = registry.findCharacterIdBySessionId(sessionId)
      assertNotNull(characterId)

      advanceTimeAndDistribute(1L, registry, processor)

      val event = receiveServerMessage()
      assertIs<ServerEvent>(event)
      assertEquals("character_movement_completed", event.type)
      assertEquals(characterId.value, event.payload["characterId"])
      assertEquals("forest", event.payload["locationId"])

      assertEquals(listOf(characterId), nucleus.getCharactersAtLocation(WorldLocationId("forest")))
    }
  }

  @Test
  fun e2e_invalidSelectionDoesNotChangeWorld() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-invalid-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-invalid", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      send(
        Frame.Text(
          clientSelection("sel-bad", sessionId, Sequence.ZERO, "opt_does_not_exist").toJson()
        )
      )
      val response = receiveServerMessage()
      assertIs<ServerRejected>(response)
      assertTrue(response.reason.contains("Unknown or expired choice"))

      assertTrue(nucleus.characters.isEmpty())
    }
  }

  @Test
  fun e2e_duplicateActionDoesNotRepeatWorldMutation() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    application {
      module(
        connectionRegistry = registry, actionProcessor = processor, offerRegistry = SessionOfferRegistry()
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-duplicate-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-duplicate", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
        )
      )
      assertIs<PresentationSnapshot>(receiveServerMessage())
      val first = receiveServerMessage()
      assertIs<ServerCompleted>(first)
      assertEquals(1, first.facts.count { it.name == "character_entered" })

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence(1), "opt_enter_world").toJson()
        )
      )
      send(Frame.Text(clientSelection("sel-move", sessionId, Sequence(1), "opt_move").toJson()))

      assertIs<PresentationSnapshot>(receiveServerMessage())
      val accepted = receiveServerMessage()
      assertIs<ServerAccepted>(accepted)
      assertTrue(accepted.detail?.contains("targetLocationId") == true)

      val characterId = registry.findCharacterIdBySessionId(sessionId)
      assertNotNull(characterId)
      assertEquals(listOf(characterId), nucleus.getCharactersAtLocation(WorldLocationId("spawn")))
    }
  }

  @Test
  fun e2e_disconnectCleansCharacterAndSessionState() = testApplication {
    val nucleus = buildE2ENucleus()
    val processor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()
    val offerRegistry = SessionOfferRegistry()
    val pendingRegistry = SessionPendingRegistry()

    application {
      module(
        connectionRegistry = registry,
        actionProcessor = processor,
        offerRegistry = offerRegistry,
        pendingRegistry = pendingRegistry
      )
    }

    val wsClient = createClient { install(WebSockets) }
    val sessionId = SessionId("e2e-disconnect-session")

    wsClient.webSocket("/game") {
      send(Frame.Text(clientHello("hello-disconnect", sessionId, Sequence.ZERO).toJson()))
      assertIs<ServerWelcome>(receiveServerMessage())
      assertIs<PresentationSnapshot>(receiveServerMessage())

      send(
        Frame.Text(
          clientSelection("sel-enter", sessionId, Sequence.ZERO, "opt_enter_world").toJson()
        )
      )
      assertIs<PresentationSnapshot>(receiveServerMessage())
      assertIs<ServerCompleted>(receiveServerMessage())

      assertTrue(nucleus.characters.isNotEmpty())

      close()
    }

    withTimeout(5_000) {
      while (registry.activeConnections().isNotEmpty()) {
        delay(10)
      }
    }

    assertNull(registry.findBySessionId(sessionId))
    assertNull(offerRegistry.find(sessionId))
    assertNull(pendingRegistry.find(sessionId))
    assertTrue(nucleus.characters.isEmpty())
  }
}