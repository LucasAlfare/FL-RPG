package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class SequenceStatus {
  EXPECTED, DUPLICATE, GAP, STALE, OUT_OF_ORDER;

  val isAccepted: Boolean get() = this == EXPECTED
  val isDuplicate: Boolean get() = this == DUPLICATE
  val isOutOfOrder: Boolean get() = this == GAP || this == STALE || this == OUT_OF_ORDER
}

class SequenceTracker(
  initialExpectedSequence: Sequence = Sequence.ZERO
) {
  var expectedSequence: Sequence = initialExpectedSequence
    private set

  private val processedMessageIds = ConcurrentHashMap.newKeySet<MessageId>()

  @Synchronized
  fun process(message: ProtocolMessage): SequenceStatus = process(message.messageId, message.sequence)

  @Synchronized
  fun process(messageId: MessageId, sequence: Sequence): SequenceStatus {
    if (processedMessageIds.contains(messageId)) {
      return SequenceStatus.DUPLICATE
    }

    val distance = expectedSequence.distanceTo(sequence)
    return when {
      distance == 0L -> {
        processedMessageIds.add(messageId)
        expectedSequence = sequence.next()
        SequenceStatus.EXPECTED
      }

      distance > 0L && distance <= Sequence.HALF_MAX_VALUE -> {
        SequenceStatus.GAP
      }

      else -> {
        SequenceStatus.STALE
      }
    }
  }

  fun isProcessed(messageId: MessageId): Boolean = processedMessageIds.contains(messageId)

  @Synchronized
  fun reset(newExpectedSequence: Sequence = Sequence.ZERO) {
    expectedSequence = newExpectedSequence
    processedMessageIds.clear()
  }
}

class SessionSequenceTracker(
  val sessionId: SessionId,
  initialClientSequence: Sequence = Sequence.ZERO,
  initialServerSequence: Sequence = Sequence.ZERO
) {
  val clientToServer = SequenceTracker(initialClientSequence)
  val serverToClient = SequenceTracker(initialServerSequence)

  fun processInbound(message: ClientMessage): SequenceStatus = clientToServer.process(message)

  fun processInbound(message: ServerMessage): SequenceStatus = serverToClient.process(message)

  fun reset(
    clientSequence: Sequence = Sequence.ZERO, serverSequence: Sequence = Sequence.ZERO
  ) {
    clientToServer.reset(clientSequence)
    serverToClient.reset(serverSequence)
  }
}