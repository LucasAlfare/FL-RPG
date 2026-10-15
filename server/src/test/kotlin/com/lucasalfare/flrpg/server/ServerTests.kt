package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.CharacterId
import com.lucasalfare.flrpg.gamedomain.Interaction
import com.lucasalfare.flrpg.gamedomain.InteractionId
import java.util.*
import kotlin.test.*

private class InMemoryConnection(
  override val id: String = UUID.randomUUID().toString(),
  override var sessionId: SessionId? = null,
  override var characterId: CharacterId? = null
) : Connection {
  override suspend fun send(message: ServerMessage) {}
  override suspend fun receive(): ClientMessage? = null
  override suspend fun close() {}
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
}