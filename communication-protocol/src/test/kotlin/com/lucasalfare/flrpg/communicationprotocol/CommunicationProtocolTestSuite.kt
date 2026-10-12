package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap

class CommunicationProtocolTestSuite {

  // =========================================================================
  // FIXTURES & SETUP
  // =========================================================================
  companion object Fixtures {
    fun createMetadata(
      msgId: String = "msg-${java.util.UUID.randomUUID()}",
      sessId: String = "sess-test",
      seq: Long = 0L,
      corrId: String? = null
    ) = MessageMetadata(
      messageId = MessageId(msgId),
      sessionId = SessionId(sessId),
      sequence = Sequence(seq),
      correlationId = corrId?.let { CorrelationId(it) }
    )

    fun createDummyDocument(rev: Long = 0L) = PresentationDocument(
      revision = Revision(rev),
      blocks = listOf(
        PresentationBlock(
          id = BlockId("block-1"),
          content = BlockContent.Text("Hello World", TextStyle.NORMAL)
        )
      )
    )
  }

  // =========================================================================
  // 1. UNIT TESTS - BASIC TYPES & SEQUENCE LOGIC
  // =========================================================================
  @Nested
  inner class BasicTypesAndSequenceTests {
    @Test
    fun `Sequence wrap-around behavior`() {
      val maxSeq = Sequence(Long.MAX_VALUE)
      val nextSeq = maxSeq.next()
      assertEquals(Sequence.ZERO, nextSeq)
    }

    @Test
    fun `Sequence distance calculation`() {
      val seq1 = Sequence(10L)
      val seq2 = Sequence(15L)
      assertEquals(5L, seq1.distanceTo(seq2))

      // Considering boundary wrap-around distance
      val seqMax = Sequence(Long.MAX_VALUE - 2)
      val seqWrap = Sequence(2L)
      assertEquals(5L, seqMax.distanceTo(seqWrap))
    }

    @Test
    fun `ProtocolVersion compatibility check`() {
      val v1 = ProtocolVersion(1, 0)
      val v1_1 = ProtocolVersion(1, 1)
      val v2 = ProtocolVersion(2, 0)

      assertTrue(v1.isCompatibleWith(v1_1))
      assertFalse(v1.isCompatibleWith(v2))
    }
  }

  // =========================================================================
  // 2. UNIT TESTS - TRACKERS & STATE MANAGERS
  // =========================================================================
  @Nested
  inner class StateAndTrackerTests {
    @Test
    fun `SequenceTracker correctly identifies expected, gap, and duplicate`() {
      val tracker = SequenceTracker(Sequence.ZERO)
      val msgId1 = MessageId("msg-1")
      val msgId2 = MessageId("msg-2")

      // Expected
      assertEquals(SequenceStatus.EXPECTED, tracker.process(msgId1, Sequence(0L)))
      assertEquals(Sequence(1L), tracker.expectedSequence)

      // Duplicate
      assertEquals(SequenceStatus.DUPLICATE, tracker.process(msgId1, Sequence(0L)))

      // Gap
      assertEquals(SequenceStatus.GAP, tracker.process(msgId2, Sequence(5L)))

      // Stale (old/out-of-order)
      assertEquals(SequenceStatus.STALE, tracker.process(MessageId("msg-3"), Sequence(0L)))
    }

    @Test
    fun `SessionLifecycleHandler accepts valid sessions and rejects incompatible versions`() {
      val handler = SessionLifecycleHandler(ProtocolVersion.V1)

      // New Session
      val hello = ClientHello(createMetadata(sessId = "new-sess"))
      val (state, response) = handler.processHello(hello, MessageId("reply-1"))

      assertEquals(SessionState.ESTABLISHED, state)
      assertTrue(response is ServerWelcome)
      assertFalse((response as ServerWelcome).isResumed)

      // Incompatible Version
      val badHello = ClientHello(createMetadata(sessId = "bad-sess").copy(version = ProtocolVersion(2, 0)))
      val (badState, badResponse) = handler.processHello(badHello, MessageId("reply-2"))

      assertEquals(SessionState.REJECTED, badState)
      assertTrue(badResponse is ServerReject)
      assertEquals(RejectionReason.INCOMPATIBLE_VERSION, (badResponse as ServerReject).reason)
    }
  }

  // =========================================================================
  // 3. UNIT TESTS - PRESENTATION DOCUMENT & PATCHES
  // =========================================================================
  @Nested
  inner class PresentationDocumentTests {
    @Test
    fun `applyPatch correctly updates (upsert), appends, and removes blocks`() {
      val initialDoc = createDummyDocument(rev = 0L)

      val newBlock = PresentationBlock(BlockId("block-2"), BlockContent.Text("Appended!"))
      val updateBlock = PresentationBlock(BlockId("block-1"), BlockContent.Text("Updated Text"))

      val patch = PresentationPatch(
        metadata = createMetadata(),
        baseRevision = Revision(0L),
        newRevision = Revision(1L),
        operations = listOf(
          PatchOperation.Append(newBlock),
          PatchOperation.Upsert(updateBlock)
        )
      )

      val newDoc = initialDoc.applyPatch(patch)

      assertEquals(Revision(1L), newDoc.revision)
      assertEquals(2, newDoc.blocks.size)
      assertEquals("Updated Text", (newDoc.blocks[0].content as BlockContent.Text).text)
      assertEquals("Appended!", (newDoc.blocks[1].content as BlockContent.Text).text)

      // Removing block
      val removePatch = PresentationPatch(
        metadata = createMetadata(),
        baseRevision = Revision(1L),
        newRevision = Revision(2L),
        operations = listOf(PatchOperation.Remove(BlockId("block-2")))
      )
      val finalDoc = newDoc.applyPatch(removePatch)
      assertEquals(1, finalDoc.blocks.size)
    }

    @Test
    fun `applyPatch throws exception on revision mismatch`() {
      val initialDoc = createDummyDocument(rev = 0L)
      val patch = PresentationPatch(
        metadata = createMetadata(),
        baseRevision = Revision(5L), // Forced mismatch
        newRevision = Revision(6L)
      )

      assertThrows<IllegalArgumentException> {
        initialDoc.applyPatch(patch)
      }
    }
  }

  // =========================================================================
  // 4. INTEGRATION TESTS
  // =========================================================================
  @Nested
  inner class IntegrationTests {
    @Test
    fun `Full JSON serialization and deserialization cycle`() {
      val originalMessage = ClientAction(
        metadata = createMetadata(sessId = "sess-int", seq = 1L),
        actionId = "CAST_SPELL",
        parameters = mapOf("spell" to "fireball", "target" to "dragon")
      )

      val jsonString = originalMessage.toJson()
      val deserializedMessage = jsonString.toProtocolMessage<ClientMessage>()

      assertTrue(deserializedMessage is ClientAction)
      val action = deserializedMessage as ClientAction
      assertEquals("CAST_SPELL", action.actionId)
      assertEquals("fireball", action.parameters["spell"])
    }

    @Test
    fun `Integration with structural validation pipeline`() {
      val validator = StructuralValidator()
      val validAction = ClientAction(createMetadata(), "JUMP")
      assertTrue(validator.validate(validAction).isValid)

      val validTextCommand = ClientTextCommand(createMetadata(), "A".repeat(4000))
      assertTrue(validator.validate(validTextCommand).isValid)
    }
  }

  // =========================================================================
  // 5. STRESS TESTS (Real-world Multi-user Scenarios)
  // =========================================================================
  @Nested
  inner class StressTests {

    @Test
    fun `Load Test - Concurrent Multi-user Processing`() = runBlocking {
      val numberOfUsers = 150
      val messagesPerUser = 500

      val lifecycleHandler = SessionLifecycleHandler()
      val globalSequenceTrackers = ConcurrentHashMap<SessionId, SequenceTracker>()

      // Simulating multiple clients connecting and sending commands simultaneously
      val jobs = (1..numberOfUsers).map { userId ->
        launch(Dispatchers.Default) {
          val sessionId = SessionId("user-$userId")

          // 1. Connect concurrently
          val hello = ClientHello(createMetadata(sessId = sessionId.value, seq = 0L))
          lifecycleHandler.processHello(hello, MessageId("reply-hello-$userId"))

          val tracker = SequenceTracker()
          globalSequenceTrackers[sessionId] = tracker

          // 2. Send message bursts (simulating intense gameplay)
          for (seq in 1..messagesPerUser) {
            val msgId = MessageId("msg-$userId-$seq")

            // Process concurrently on server
            val status = tracker.process(msgId, Sequence(seq.toLong() - 1))

            assertEquals(SequenceStatus.EXPECTED, status)
          }
        }
      }

      jobs.joinAll()

      // Post-load structural consistency assertions
      assertEquals(numberOfUsers, globalSequenceTrackers.size)
      globalSequenceTrackers.values.forEach { tracker ->
        assertEquals(Sequence(messagesPerUser.toLong()), tracker.expectedSequence)
      }
    }

    @Test
    fun `Load Test - High Throughput Presentation Patches`() = runBlocking {
      var sharedDoc = PresentationDocument(Revision.ZERO, emptyList())
      val patchCount = 2000

      val patchJobs = (1..patchCount).map { i ->
        // Creating patches (simulating heavy server payloads)
        PresentationPatch(
          metadata = createMetadata(seq = i.toLong()),
          baseRevision = Revision(i.toLong() - 1),
          newRevision = Revision(i.toLong()),
          operations = listOf(
            PatchOperation.Upsert(
              PresentationBlock(BlockId("stress-block"), BlockContent.Text("State $i"))
            )
          )
        )
      }

      // Simulates client consuming patch queue as fast as possible
      val eventLoopJob = launch(Dispatchers.Unconfined) {
        patchJobs.forEach { patch ->
          sharedDoc = sharedDoc.applyPatch(patch)
        }
      }

      eventLoopJob.join()

      // Verify no race conditions occurred on immutable model and final revision matches
      assertEquals(Revision(patchCount.toLong()), sharedDoc.revision)
      assertEquals(1, sharedDoc.blocks.size)
      assertEquals("State $patchCount", (sharedDoc.blocks[0].content as BlockContent.Text).text)
    }

  }
}
