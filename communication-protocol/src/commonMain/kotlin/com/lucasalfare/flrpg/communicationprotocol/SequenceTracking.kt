package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.Serializable

@Serializable
enum class SequenceStatus {
  EXPECTED, DUPLICATE, GAP, STALE, OUT_OF_ORDER;

  val isAccepted: Boolean get() = this == EXPECTED
  val isDuplicate: Boolean get() = this == DUPLICATE
  val isOutOfOrder: Boolean get() = this == GAP || this == STALE || this == OUT_OF_ORDER
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class PlatformLock() {
  inline fun <T> lock(block: () -> T): T
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class ThreadSafeMessageIdSet() {
  fun contains(element: MessageId): Boolean
  fun add(element: MessageId): Boolean
  fun clear()
}

class SequenceTracker(
  initialExpectedSequence: Sequence = Sequence.ZERO
) {
  var expectedSequence: Sequence = initialExpectedSequence
    private set

  private val processedMessageIds = ThreadSafeMessageIdSet()
  private val lock = PlatformLock()

  fun process(message: ProtocolMessage): SequenceStatus = lock.lock {
    process(message.messageId, message.sequence)
  }

  fun process(messageId: MessageId, sequence: Sequence): SequenceStatus = lock.lock {
    if (processedMessageIds.contains(messageId)) {
      return@lock SequenceStatus.DUPLICATE
    }

    val distance = expectedSequence.distanceTo(sequence)
    return@lock when {
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

  fun isProcessed(messageId: MessageId): Boolean = lock.lock {
    processedMessageIds.contains(messageId)
  }

  fun reset(newExpectedSequence: Sequence = Sequence.ZERO) = lock.lock {
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