package com.lucasalfare.flrpg.communicationprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunicationProtocolTests {

  @Test
  fun sequence_nextWrapsAroundAtLongMaxValue() {
    val max = Sequence(Long.MAX_VALUE)
    val next = max.next()
    assertEquals(Sequence.ZERO, next)
  }

  @Test
  fun sequence_distanceHandlesWrapAround() {
    val nearMax = Sequence(Long.MAX_VALUE - 2)
    val afterWrap = Sequence(3)
    assertEquals(6L, nearMax.distanceTo(afterWrap))

    val zero = Sequence.ZERO
    assertEquals(5L, zero.distanceTo(Sequence(5)))
    assertEquals(0L, zero.distanceTo(zero))
  }

  @Test
  fun sequenceTracker_acceptsExpectedSequence() {
    val tracker = SequenceTracker(Sequence.ZERO)
    val messageId = MessageId("msg-1")

    val status = tracker.process(messageId, Sequence.ZERO)

    assertEquals(SequenceStatus.EXPECTED, status)
    assertTrue(status.isAccepted)
    assertEquals(Sequence(1), tracker.expectedSequence)
    assertTrue(tracker.isProcessed(messageId))
  }

  @Test
  fun sequenceTracker_rejectsDuplicateMessageId() {
    val tracker = SequenceTracker(Sequence.ZERO)
    val messageId = MessageId("msg-1")

    val first = tracker.process(messageId, Sequence.ZERO)
    assertEquals(SequenceStatus.EXPECTED, first)
    assertEquals(Sequence(1), tracker.expectedSequence)

    val second = tracker.process(messageId, Sequence(1))
    assertEquals(SequenceStatus.DUPLICATE, second)
    assertTrue(second.isDuplicate)
    assertEquals(Sequence(1), tracker.expectedSequence)
  }

  @Test
  fun sequenceTracker_reportsGapWithoutAdvancing() {
    val tracker = SequenceTracker(Sequence.ZERO)
    val messageId = MessageId("msg-gap")

    val status = tracker.process(messageId, Sequence(5))

    assertEquals(SequenceStatus.GAP, status)
    assertTrue(status.isOutOfOrder)
    assertEquals(Sequence.ZERO, tracker.expectedSequence)
    assertFalse(tracker.isProcessed(messageId))
  }

  @Test
  fun sequenceTracker_reportsStaleSequence() {
    val tracker = SequenceTracker(Sequence(10))
    val messageId = MessageId("msg-stale")

    val status = tracker.process(messageId, Sequence(1))

    assertEquals(SequenceStatus.STALE, status)
    assertTrue(status.isOutOfOrder)
    assertEquals(Sequence(10), tracker.expectedSequence)
    assertFalse(tracker.isProcessed(messageId))
  }

  @Test
  fun sequenceTracker_resetClearsProcessedMessages() {
    val tracker = SequenceTracker(Sequence.ZERO)
    val messageId = MessageId("msg-1")

    tracker.process(messageId, Sequence.ZERO)
    assertTrue(tracker.isProcessed(messageId))
    assertEquals(Sequence(1), tracker.expectedSequence)

    tracker.reset(Sequence(42))
    assertFalse(tracker.isProcessed(messageId))
    assertEquals(Sequence(42), tracker.expectedSequence)
  }

  @Test
  fun sessionSequenceTracker_keepsDirectionsIndependent() {
    val sessionId = SessionId("session-1")
    val tracker = SessionSequenceTracker(sessionId)

    val clientMessageId = MessageId("client-1")
    val serverMessageId = MessageId("server-1")

    val clientStatus = tracker.clientToServer.process(clientMessageId, Sequence.ZERO)
    assertEquals(SequenceStatus.EXPECTED, clientStatus)
    assertEquals(Sequence(1), tracker.clientToServer.expectedSequence)
    assertEquals(Sequence.ZERO, tracker.serverToClient.expectedSequence)

    val serverStatus = tracker.serverToClient.process(serverMessageId, Sequence.ZERO)
    assertEquals(SequenceStatus.EXPECTED, serverStatus)
    assertEquals(Sequence(1), tracker.clientToServer.expectedSequence)
    assertEquals(Sequence(1), tracker.serverToClient.expectedSequence)

    assertTrue(tracker.clientToServer.isProcessed(clientMessageId))
    assertFalse(tracker.clientToServer.isProcessed(serverMessageId))
    assertTrue(tracker.serverToClient.isProcessed(serverMessageId))
    assertFalse(tracker.serverToClient.isProcessed(clientMessageId))
  }

  @Test
  fun protocolVersion_acceptsCompatibleMajorVersion() {
    val v1_0 = ProtocolVersion(1, 0)
    val v1_5 = ProtocolVersion(1, 5)

    assertTrue(v1_0.isCompatibleWith(v1_5))
    assertTrue(v1_5.isCompatibleWith(v1_0))
    assertTrue(v1_0.isCompatibleWith(ProtocolVersion.V1))
  }

  @Test
  fun protocolVersion_rejectsIncompatibleMajorVersion() {
    val v1 = ProtocolVersion(1, 0)
    val v2 = ProtocolVersion(2, 0)

    assertFalse(v1.isCompatibleWith(v2))
    assertFalse(v2.isCompatibleWith(v1))
  }
}
