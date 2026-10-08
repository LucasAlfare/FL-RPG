package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.ClientAction
import com.lucasalfare.flrpg.communicationprotocol.ClientHello
import com.lucasalfare.flrpg.communicationprotocol.ClientMessage
import com.lucasalfare.flrpg.communicationprotocol.MessageId
import com.lucasalfare.flrpg.communicationprotocol.MessageMetadata
import com.lucasalfare.flrpg.communicationprotocol.Sequence
import com.lucasalfare.flrpg.communicationprotocol.ServerCompleted
import com.lucasalfare.flrpg.communicationprotocol.ServerEvent
import com.lucasalfare.flrpg.communicationprotocol.ServerMessage
import com.lucasalfare.flrpg.communicationprotocol.ServerWelcome
import com.lucasalfare.flrpg.communicationprotocol.SessionId
import com.lucasalfare.flrpg.gamedomain.CharacterId
import com.lucasalfare.flrpg.gamedomain.GameActionProcessor
import com.lucasalfare.flrpg.gamedomain.Nucleus
import com.lucasalfare.flrpg.gamedomain.NucleusId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class TestConnection(
  override val id: String = UUID.randomUUID().toString(),
  override var sessionId: SessionId? = null,
  override var characterId: CharacterId? = null
) : Connection {
  val inbound = Channel<ClientMessage>(Channel.UNLIMITED)
  val outbound = ConcurrentLinkedQueue<ServerMessage>()

  override suspend fun send(message: ServerMessage) {
    outbound.add(message)
  }

  override suspend fun receive(): ClientMessage? {
    return inbound.receiveCatching().getOrNull()
  }

  override suspend fun close() {
    inbound.close()
  }
}

class PipelineIntegrationTest {

  @Test
  fun testFullVerticalPipeline() = runTest {
    val nucleus = Nucleus(NucleusId("main_nucleus"))
    nucleus.connectLocations("spawn", "loc2")

    val actionProcessor = GameActionProcessor(nucleus)
    val registry = ConnectionRegistry()

    val conn1 = TestConnection()
    val conn2 = TestConnection()

    val job1 = launch { handleConnection(conn1, registry, actionProcessor = actionProcessor) }
    val job2 = launch { handleConnection(conn2, registry, actionProcessor = actionProcessor) }

    conn1.inbound.send(
      ClientHello(
        metadata = MessageMetadata(MessageId("h1"), SessionId("s1"), Sequence.ZERO)
      )
    )
    yield()
    val welcome1 = conn1.outbound.poll() as ServerWelcome
    val session1 = welcome1.metadata.sessionId
    registry.bindCharacter(session1, CharacterId("char1"))

    conn2.inbound.send(
      ClientHello(
        metadata = MessageMetadata(MessageId("h2"), SessionId("s2"), Sequence.ZERO)
      )
    )
    yield()
    val welcome2 = conn2.outbound.poll() as ServerWelcome
    val session2 = welcome2.metadata.sessionId
    registry.bindCharacter(session2, CharacterId("char2"))

    conn2.inbound.send(
      ClientAction(
        metadata = MessageMetadata(MessageId("a1"), session2, Sequence.ZERO),
        actionId = "enter_world",
        parameters = mapOf("locationId" to "spawn")
      )
    )
    yield()
    val respConn2Enter = conn2.outbound.poll() as ServerCompleted
    assertEquals(session2, respConn2Enter.metadata.sessionId)
    assertTrue(nucleus.containsCharacter(CharacterId("char2")))

    conn1.inbound.send(
      ClientAction(
        metadata = MessageMetadata(MessageId("a2"), session1, Sequence.ZERO),
        actionId = "enter_world",
        parameters = mapOf("locationId" to "spawn")
      )
    )
    yield()
    val respConn1Enter = conn1.outbound.poll() as ServerCompleted
    assertEquals(session1, respConn1Enter.metadata.sessionId)
    assertTrue(nucleus.containsCharacter(CharacterId("char1")))

    val eventChar1Entered = conn2.outbound.poll() as? ServerEvent
    assertNotNull(eventChar1Entered)
    assertEquals("character_entered", eventChar1Entered.type)
    assertEquals("char1", eventChar1Entered.payload["characterId"])
    assertEquals("spawn", eventChar1Entered.payload["locationId"])

    conn1.inbound.send(
      ClientAction(
        metadata = MessageMetadata(MessageId("a3"), session1, Sequence(1L)),
        actionId = "move",
        parameters = mapOf("targetLocationId" to "loc2")
      )
    )
    yield()
    val respConn1Move = conn1.outbound.poll() as ServerCompleted
    assertEquals(session1, respConn1Move.metadata.sessionId)

    val eventChar1Moved = conn2.outbound.poll() as? ServerEvent
    assertNotNull(eventChar1Moved)
    assertEquals("character_moved", eventChar1Moved.type)
    assertEquals("char1", eventChar1Moved.payload["characterId"])

    advanceTimeAndDistribute(ticks = 1L, registry = registry, actionProcessor = actionProcessor)

    val eventMoveCompleted = conn1.outbound.poll() as? ServerEvent
    assertNotNull(eventMoveCompleted)
    assertEquals("character_movement_completed", eventMoveCompleted.type)
    assertEquals("char1", eventMoveCompleted.payload["characterId"])
    assertEquals("loc2", eventMoveCompleted.payload["locationId"])

    conn1.close()
    conn2.close()
    job1.cancel()
    job2.cancel()
  }
}