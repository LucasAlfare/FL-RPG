package com.lucasalfare.flrpg.gamedomain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameDomainTests {

  @Test
  fun gameActionProcessor_executesRegisteredAction() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    val result = processor.process(
      GameAction(
        name = "enter_world", actorId = CharacterId("hero"), arguments = mapOf("locationId" to "spawn")
      )
    )

    assertTrue(result is GameActionResult.Success)
    assertTrue(nucleus.containsCharacter(CharacterId("hero")))
  }

  @Test
  fun gameActionProcessor_rejectsUnknownAction() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    val result = processor.process(
      GameAction(name = "unknown_action", actorId = CharacterId("hero"))
    )

    assertTrue(result is GameActionResult.Rejected)
    assertTrue(!nucleus.containsCharacter(CharacterId("hero")))
  }

  @Test
  fun gameActionProcessor_rejectsDuplicateHandlerNames() {
    val nucleus = Nucleus(NucleusId("nucleus"))

    assertFailsWith<IllegalArgumentException> {
      GameActionProcessor(
        nucleus = nucleus, handlers = listOf(EnterWorldHandler(), EnterWorldHandler())
      )
    }
  }

  @Test
  fun gameActionProcessor_preservesActorIdentity() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("unique-hero-id")

    val result = processor.process(
      GameAction(
        name = "enter_world", actorId = actorId, arguments = mapOf("locationId" to "spawn")
      )
    )

    val success = result as GameActionResult.Success
    val fact = success.facts.single()
    assertEquals(actorId.value, fact.arguments["characterId"])
  }

  @Test
  fun gameActionProcessor_returnsExpectedResult() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    val result = processor.process(
      GameAction(
        name = "enter_world", actorId = CharacterId("hero"), arguments = mapOf("locationId" to "spawn")
      )
    )

    val success = result as GameActionResult.Success
    assertEquals(1, success.facts.size)
    val fact = success.facts.single()
    assertEquals("character_entered", fact.name)
    assertEquals("hero", fact.arguments["characterId"])
    assertEquals("spawn", fact.arguments["locationId"])
  }

  @Test
  fun defaultInteractionProvider_offersEnterWorldForUnregisteredCharacter() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    val interactions = processor.availableInteractions(CharacterId("newbie"))

    assertEquals(1, interactions.size)
    assertEquals("enter_world", interactions.single().actionName)
  }

  @Test
  fun defaultInteractionProvider_offersMovementForRegisteredCharacter() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    processor.process(
      GameAction(
        name = "enter_world", actorId = CharacterId("hero"), arguments = mapOf("locationId" to "spawn")
      )
    )

    val interactions = processor.availableInteractions(CharacterId("hero"))

    assertEquals(1, interactions.size)
    assertEquals("move", interactions.single().actionName)
  }

  @Test
  fun defaultInteractionProvider_doesNotOfferUnavailableInteractions() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    val unregistered = processor.availableInteractions(CharacterId("newbie"))
    assertTrue(unregistered.none { it.actionName == "move" })

    processor.process(
      GameAction(
        name = "enter_world", actorId = CharacterId("hero"), arguments = mapOf("locationId" to "spawn")
      )
    )

    val registered = processor.availableInteractions(CharacterId("hero"))
    assertTrue(registered.none { it.actionName == "enter_world" })
  }

  @Test
  fun gameActionProcessor_calculatesFactAudiencesByLocation() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    processor.process(
      GameAction("enter_world", CharacterId("a"), mapOf("locationId" to "spawn"))
    )
    processor.process(
      GameAction("enter_world", CharacterId("b"), mapOf("locationId" to "spawn"))
    )
    processor.process(
      GameAction("enter_world", CharacterId("c"), mapOf("locationId" to "town"))
    )

    val fact = GameFact("event", mapOf("locationId" to "spawn"))
    val audiences = processor.findFactAudiences(listOf(fact))

    assertEquals(1, audiences.size)
    assertEquals(
      setOf(CharacterId("a"), CharacterId("b")), audiences.single().characterIds.toSet()
    )
  }

  @Test
  fun gameActionProcessor_doesNotAssignAudienceToLocationlessFact() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    processor.process(
      GameAction(
        name = "enter_world", actorId = CharacterId("hero"), arguments = mapOf("locationId" to "spawn")
      )
    )

    val fact = GameFact("global_event", mapOf("characterId" to "hero"))
    val audiences = processor.findFactAudiences(listOf(fact))

    assertEquals(1, audiences.size)
    assertTrue(audiences.single().characterIds.isEmpty())
  }

  @Test
  fun gameActionProcessor_doesNotDuplicateFactRecipients() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    processor.process(
      GameAction("enter_world", CharacterId("a"), mapOf("locationId" to "spawn"))
    )
    processor.process(
      GameAction("enter_world", CharacterId("b"), mapOf("locationId" to "spawn"))
    )

    val fact = GameFact("event", mapOf("locationId" to "spawn"))
    val audience = processor.findFactAudiences(listOf(fact)).single()

    assertEquals(audience.characterIds.size, audience.characterIds.toSet().size)
  }
}