package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldEntityId
import com.lucasalfare.flworld.WorldLocationId
import kotlinx.coroutines.*
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

  @Test
  fun enterWorld_createsCharacterAndWorldEntity() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)

    val result = processor.process(
      GameAction(
        name = "enter_world", actorId = CharacterId("hero"), arguments = mapOf("locationId" to "spawn")
      )
    )

    assertTrue(result is GameActionResult.Success)
    assertTrue(nucleus.containsCharacter(CharacterId("hero")))
    assertEquals(
      WorldLocationId("spawn"), nucleus.world.state.getLocation(WorldEntityId("hero"))
    )
  }

  @Test
  fun enterWorld_rejectsDuplicateEntryWithoutChangingState() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val entityId = WorldEntityId(actorId.value)

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )

    val result = processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "town"))
    )

    assertTrue(result is GameActionResult.Rejected)
    assertTrue(result.facts.isEmpty())
    assertTrue(nucleus.containsCharacter(actorId))
    assertEquals(WorldLocationId("spawn"), nucleus.world.state.getLocation(entityId))
    assertTrue(nucleus.world.getActiveMovements().isEmpty())
  }

  @Test
  fun move_rejectsMissingOrInvalidDestination() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val entityId = WorldEntityId(actorId.value)

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )

    val missing = processor.process(GameAction("move", actorId))
    val invalid = processor.process(
      GameAction("move", actorId, mapOf("targetLocationId" to "nowhere"))
    )

    assertTrue(missing is GameActionResult.Rejected)
    assertTrue(invalid is GameActionResult.Rejected)
    assertEquals(WorldLocationId("spawn"), nucleus.world.state.getLocation(entityId))
    assertTrue(nucleus.world.getActiveMovements().isEmpty())
  }

  @Test
  fun move_rejectsMovementWithoutValidPath() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val entityId = WorldEntityId(actorId.value)

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )
    nucleus.registerLocation("town")

    val result = processor.process(
      GameAction("move", actorId, mapOf("targetLocationId" to "town"))
    )

    assertTrue(result is GameActionResult.Rejected)
    assertEquals(WorldLocationId("spawn"), nucleus.world.state.getLocation(entityId))
    assertTrue(nucleus.world.getActiveMovements().isEmpty())
  }

  @Test
  fun move_startsMovementWithoutTeleporting() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val entityId = WorldEntityId(actorId.value)

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )
    nucleus.connectLocations("spawn", "town")

    val result = processor.process(
      GameAction("move", actorId, mapOf("targetLocationId" to "town"))
    )

    assertTrue(result is GameActionResult.Success)
    assertEquals(WorldLocationId("spawn"), nucleus.world.state.getLocation(entityId))

    val movement = nucleus.world.getMovement(entityId)
    assertEquals(movement?.isInProgress, true)
    assertEquals(WorldLocationId("town"), movement?.destination)
  }

  @Test
  fun advanceTime_completesMovementAndProducesFact() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val entityId = WorldEntityId(actorId.value)

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )
    nucleus.connectLocations("spawn", "town")
    processor.process(
      GameAction("move", actorId, mapOf("targetLocationId" to "town"))
    )

    val facts = processor.advanceTime(10)

    assertEquals(1, facts.size)
    val fact = facts.single()
    assertEquals("character_movement_completed", fact.name)
    assertEquals("hero", fact.arguments["characterId"])
    assertEquals("spawn", fact.arguments["originLocationId"])
    assertEquals("town", fact.arguments["locationId"])
    assertEquals(WorldLocationId("town"), nucleus.world.state.getLocation(entityId))
  }

  @Test
  fun advanceTime_doesNotCompleteMovementTooEarly() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val entityId = WorldEntityId(actorId.value)

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )
    nucleus.connectLocations("spawn", "middle")
    nucleus.connectLocations("middle", "town")
    processor.process(
      GameAction("move", actorId, mapOf("targetLocationId" to "town"))
    )

    val facts = processor.advanceTime(1)

    assertTrue(facts.isEmpty())
    assertEquals(WorldLocationId("spawn"), nucleus.world.state.getLocation(entityId))
    assertEquals(nucleus.world.getMovement(entityId)?.isInProgress, true)
  }

  @Test
  fun movementCompletionProducesExpectedFactOnce() {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")

    processor.process(
      GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
    )
    nucleus.connectLocations("spawn", "town")
    processor.process(
      GameAction("move", actorId, mapOf("targetLocationId" to "town"))
    )

    val firstFacts = processor.advanceTime(10)
    val secondFacts = processor.advanceTime(10)

    assertEquals(1, firstFacts.count { it.name == "character_movement_completed" })
    assertEquals(0, secondFacts.count { it.name == "character_movement_completed" })
  }

  @Test
  fun gameActionProcessor_concurrentEntryOfSameCharacterSucceedsOnlyOnce() = runBlocking {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val actorId = CharacterId("hero")
    val gate = CompletableDeferred<Unit>()
    val contenders = 32

    val jobs = (1..contenders).map {
      async(Dispatchers.Default) {
        gate.await()
        processor.process(
          GameAction("enter_world", actorId, mapOf("locationId" to "spawn"))
        )
      }
    }

    gate.complete(Unit)
    val outcomes = jobs.awaitAll()

    assertEquals(1, outcomes.count { it is GameActionResult.Success })
    assertEquals(contenders - 1, outcomes.count { it is GameActionResult.Rejected })
    assertTrue(nucleus.containsCharacter(actorId))
    assertEquals(WorldLocationId("spawn"), nucleus.world.state.getLocation(WorldEntityId(actorId.value)))
    assertEquals(listOf(actorId), nucleus.getCharactersAtLocation(WorldLocationId("spawn")))
  }

  @Test
  fun gameActionProcessor_concurrentEntriesAndTimeAdvancementPreserveState() = runBlocking {
    val nucleus = Nucleus(NucleusId("nucleus"))
    val processor = GameActionProcessor(nucleus)
    val gate = CompletableDeferred<Unit>()
    val entrants = 24

    val entries = (1..entrants).map { index ->
      async(Dispatchers.Default) {
        gate.await()
        processor.process(
          GameAction("enter_world", CharacterId("character-$index"), mapOf("locationId" to "spawn"))
        )
      }
    }

    val advances = (1..8).map {
      async(Dispatchers.Default) {
        gate.await()
        processor.advanceTime(1L)
      }
    }

    gate.complete(Unit)
    val entryResults = entries.awaitAll()
    advances.awaitAll()

    assertEquals(entrants, entryResults.count { it is GameActionResult.Success })
    assertEquals(entrants, nucleus.characters.size)
    assertEquals(entrants, nucleus.getCharactersAtLocation(WorldLocationId("spawn")).size)
    assertTrue(nucleus.world.getActiveMovements().isEmpty())
  }
}