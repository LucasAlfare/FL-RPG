package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldEntity
import com.lucasalfare.flworld.WorldEntityId
import com.lucasalfare.flworld.WorldLocationId
import org.junit.jupiter.api.Assertions.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestCustomHandler : GameActionHandler {
  override val actionName: String = "test_custom_action"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    val fact1 = GameFact(
      name = "test_fact_author",
      arguments = mapOf("characterId" to action.actorId.value, "locationId" to "spawn", "value" to "coliding_arg")
    )
    val fact2 = GameFact(
      name = "test_fact_shared", arguments = mapOf("locationId" to "spawn", "value" to "coliding_arg")
    )
    val fact3 = GameFact(
      name = "test_fact_no_audience", arguments = emptyMap()
    )
    return GameActionResult.Success(listOf(fact1, fact2, fact3))
  }
}

class GameActionProcessorTest {

  @Test
  fun testHandlerRegistrationAndDuplicateDetection() {
    val nucleus = Nucleus(NucleusId("test_nucleus"))
    val handler1 = TestCustomHandler()
    val handler2 = TestCustomHandler()

    val exception = assertThrows(IllegalArgumentException::class.java) {
      GameActionProcessor(nucleus, listOf(handler1, handler2))
    }
    assertTrue(exception.message!!.contains("Duplicate handler"))
  }

  @Test
  fun testUnknownActionRejection() {
    val nucleus = Nucleus(NucleusId("test_nucleus"))
    val processor = GameActionProcessor(nucleus, emptyList())
    val action = GameAction("unknown_action", CharacterId("char_1"))

    val result = processor.process(action)
    assertTrue(result is GameActionResult.Rejected)
    assertEquals("Unknown action: unknown_action", result.reason)
  }

  @Test
  fun testProcessAndAudiences() {
    val nucleus = Nucleus(NucleusId("test_nucleus"))
    nucleus.registerLocation("spawn")
    val charId = CharacterId("char_1")
    nucleus.world.registerEntity(WorldEntity(WorldEntityId(charId.value)))
    nucleus.world.state.setLocation(WorldEntityId(charId.value), WorldLocationId("spawn"))
    nucleus.registerCharacter(charId)

    val processor = GameActionProcessor(nucleus, listOf(TestCustomHandler()))
    val action = GameAction("test_custom_action", charId)

    val execution = processor.processWithAudiences(action)
    assertTrue(execution.result is GameActionResult.Success)
    assertEquals(3, execution.result.facts.size)

    assertEquals(3, execution.factAudiences.size)

    val audienceNoTarget = execution.factAudiences.find { it.fact.name == "test_fact_no_audience" }
    assertNotNull(audienceNoTarget)
    assertTrue(audienceNoTarget.characterIds.isEmpty())

    val audienceShared = execution.factAudiences.find { it.fact.name == "test_fact_shared" }
    assertNotNull(audienceShared)
    assertTrue(audienceShared.characterIds.contains(charId))
  }
}