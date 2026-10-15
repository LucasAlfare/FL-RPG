package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.ClientMessage
import com.lucasalfare.flrpg.communicationprotocol.ServerMessage
import com.lucasalfare.flrpg.communicationprotocol.SessionId
import com.lucasalfare.flrpg.gamedomain.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.*
import kotlin.test.Test
import kotlin.test.assertEquals

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

  override suspend fun receive(): ClientMessage? = inbound.receiveCatching().getOrNull()

  override suspend fun close() {
    inbound.close()
  }
}

class PipelineIntegrationTest {

  @Test
  fun concurrentActionsAndTimeAdvancementAreSerialized() {
    val nucleus = Nucleus(
      NucleusId("concurrency_test")
    )

    val processor = GameActionProcessor(nucleus)

    val executor = Executors.newFixedThreadPool(8)

    val startGate = CountDownLatch(1)

    val futures = mutableListOf<Future<*>>()

    try {
      repeat(50) { index ->
        futures.add(
          executor.submit(
            Callable {
              startGate.await()

              val result = processor.process(
                GameAction(
                  name = "enter_world", actorId = CharacterId("character_$index"), arguments = mapOf(
                    "locationId" to "spawn"
                  )
                )
              )

              check(
                result is GameActionResult.Success
              ) {
                "Character $index failed to enter the world: $result"
              }
            })
        )
      }

      repeat(100) {
        futures.add(
          executor.submit(
            Callable {
              startGate.await()
              processor.advanceTime(1L)
              Unit
            })
        )
      }

      startGate.countDown()

      futures.forEach { future ->
        future.get(10, TimeUnit.SECONDS)
      }

      assertEquals(
        50, nucleus.characters.size
      )
    } finally {
      startGate.countDown()
      executor.shutdownNow()
    }
  }
}