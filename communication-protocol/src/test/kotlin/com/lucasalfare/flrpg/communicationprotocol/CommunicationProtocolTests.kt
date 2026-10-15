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

  @Test
  fun structuralValidator_acceptsDocumentWithinLimits() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)
    val blocks = List(limits.maxBlocksPerDocument) { i ->
      PresentationBlock(
        id = BlockId("block-$i"), content = BlockContent.Text("text-$i")
      )
    }
    val document = PresentationDocument(revision = Revision.ZERO, blocks = blocks)
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = document
    )

    val result = validator.validate(snapshot)

    assertTrue(result.isValid)
  }

  @Test
  fun structuralValidator_rejectsTextAboveLimit() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)
    val longText = "x".repeat(limits.maxTextLength + 1)
    val document = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(
        PresentationBlock(
          id = BlockId("block-1"), content = BlockContent.Text(longText)
        )
      )
    )
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = document
    )

    val result = validator.validate(snapshot)

    assertFalse(result.isValid)
  }

  @Test
  fun structuralValidator_acceptsTextAtLimit() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)
    val exactText = "x".repeat(limits.maxTextLength)
    val document = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(
        PresentationBlock(
          id = BlockId("block-1"), content = BlockContent.Text(exactText)
        )
      )
    )
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = document
    )

    val result = validator.validate(snapshot)

    assertTrue(result.isValid)
  }

  @Test
  fun structuralValidator_rejectsTooManyActionParameters() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)
    val parameters = (1..(limits.maxParametersPerAction + 1)).associate { "key-$it" to "value-$it" }
    val action = ClientAction(
      metadata = MessageMetadata(
        messageId = MessageId("action-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), actionId = "test-action", parameters = parameters
    )

    val result = validator.validate(action)

    assertFalse(result.isValid)
  }

  @Test
  fun structuralValidator_rejectsOversizedDocuments() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)
    val blocks = List(limits.maxBlocksPerDocument + 1) { i ->
      PresentationBlock(
        id = BlockId("block-$i"), content = BlockContent.Text("text-$i")
      )
    }
    val document = PresentationDocument(revision = Revision.ZERO, blocks = blocks)
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = document
    )

    val result = validator.validate(snapshot)

    assertFalse(result.isValid)
  }

  @Test
  fun structuralValidator_rejectsInvalidChoicesAndFeeds() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)

    val tooManyChoices = BlockContent.Choices(
      choices = List(limits.maxChoicesPerBlock + 1) { i ->
        Choice(id = "choice-$i", text = "Choice $i")
      })
    val choicesDocument = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(PresentationBlock(id = BlockId("choices"), content = tooManyChoices))
    )
    val choicesSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-choices"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = choicesDocument
    )
    assertFalse(validator.validate(choicesSnapshot).isValid)

    val duplicateChoiceIds = BlockContent.Choices(
      choices = listOf(
        Choice(id = "same", text = "First"), Choice(id = "same", text = "Second")
      )
    )
    val dupIdsDocument = PresentationDocument(
      revision = Revision.ZERO,
      blocks = listOf(PresentationBlock(id = BlockId("dup-ids"), content = duplicateChoiceIds))
    )
    val dupIdsSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-dup-ids"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = dupIdsDocument
    )
    assertFalse(validator.validate(dupIdsSnapshot).isValid)

    val duplicateShortcuts = BlockContent.Choices(
      choices = listOf(
        Choice(id = "a", text = "A", shortcut = "1"), Choice(id = "b", text = "B", shortcut = "1")
      )
    )
    val dupShortcutsDocument = PresentationDocument(
      revision = Revision.ZERO,
      blocks = listOf(PresentationBlock(id = BlockId("dup-shortcuts"), content = duplicateShortcuts))
    )
    val dupShortcutsSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-dup-shortcuts"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = dupShortcutsDocument
    )
    assertFalse(validator.validate(dupShortcutsSnapshot).isValid)

    val tooManyFeedItems = BlockContent.Feed(
      feedId = "feed-1", items = List(limits.maxItemsPerList + 1) { i ->
        FeedItem(id = "item-$i", text = "Item $i")
      })
    val feedDocument = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(PresentationBlock(id = BlockId("feed"), content = tooManyFeedItems))
    )
    val feedSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-feed"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = feedDocument
    )
    assertFalse(validator.validate(feedSnapshot).isValid)

    val longFeedItemText = BlockContent.Feed(
      feedId = "feed-2", items = listOf(FeedItem(id = "item-1", text = "x".repeat(limits.maxTextLength + 1)))
    )
    val longFeedDocument = PresentationDocument(
      revision = Revision.ZERO,
      blocks = listOf(PresentationBlock(id = BlockId("long-feed"), content = longFeedItemText))
    )
    val longFeedSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-long-feed"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = longFeedDocument
    )
    assertFalse(validator.validate(longFeedSnapshot).isValid)
  }

  @Test
  fun structuralValidator_rejectsInvalidPatchBlocks() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)

    val tooManyOps = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-ops"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), baseRevision = Revision.ZERO, newRevision = Revision(1), operations = List(limits.maxPatchOperations + 1) {
        PatchOperation.Clear
      })
    assertFalse(validator.validate(tooManyOps).isValid)

    val longTextInUpsert = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-upsert"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), baseRevision = Revision.ZERO, newRevision = Revision(1), operations = listOf(
        PatchOperation.Upsert(
          PresentationBlock(
            id = BlockId("block-1"), content = BlockContent.Text("x".repeat(limits.maxTextLength + 1))
          )
        )
      )
    )
    assertFalse(validator.validate(longTextInUpsert).isValid)

    val longTextInAppend = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-append"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), baseRevision = Revision.ZERO, newRevision = Revision(1), operations = listOf(
        PatchOperation.Append(
          PresentationBlock(
            id = BlockId("block-2"), content = BlockContent.Text("x".repeat(limits.maxTextLength + 1))
          )
        )
      )
    )
    assertFalse(validator.validate(longTextInAppend).isValid)
  }

  @Test
  fun structuralValidator_acceptsValidBoundaryValues() {
    val limits = ProtocolLimits()
    val validator = StructuralValidator(limits)

    val maxParams = (1..limits.maxParametersPerAction).associate { "key-$it" to "value-$it" }
    val actionAtLimit = ClientAction(
      metadata = MessageMetadata(
        messageId = MessageId("action-limit"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), actionId = "test-action", parameters = maxParams
    )
    assertTrue(validator.validate(actionAtLimit).isValid)

    val maxChoices = BlockContent.Choices(
      choices = List(limits.maxChoicesPerBlock) { i ->
        Choice(id = "choice-$i", text = "Choice $i", shortcut = if (i % 2 == 0) "s$i" else null)
      })
    val choicesDocument = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(PresentationBlock(id = BlockId("choices"), content = maxChoices))
    )
    val choicesSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-choices-limit"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = choicesDocument
    )
    assertTrue(validator.validate(choicesSnapshot).isValid)

    val maxItems = BlockContent.ItemsList(
      items = List(limits.maxItemsPerList) { "item-$it" })
    val itemsDocument = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(PresentationBlock(id = BlockId("items"), content = maxItems))
    )
    val itemsSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-items-limit"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = itemsDocument
    )
    assertTrue(validator.validate(itemsSnapshot).isValid)

    val maxFeedItems = BlockContent.Feed(
      feedId = "feed-limit", items = List(limits.maxItemsPerList) { i ->
        FeedItem(id = "item-$i", text = "Item $i")
      })
    val feedDocument = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(PresentationBlock(id = BlockId("feed"), content = maxFeedItems))
    )
    val feedSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-feed-limit"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = feedDocument
    )
    assertTrue(validator.validate(feedSnapshot).isValid)

    val maxOpsPatch = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-ops-limit"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), baseRevision = Revision.ZERO, newRevision = Revision(1), operations = List(limits.maxPatchOperations) {
        PatchOperation.Clear
      })
    assertTrue(validator.validate(maxOpsPatch).isValid)

    val exactTextTitle = PresentationDocument(
      revision = Revision.ZERO, blocks = listOf(
        PresentationBlock(
          id = BlockId("titled"), content = BlockContent.Text("ok"), title = "x".repeat(limits.maxTextLength)
        )
      )
    )
    val titleSnapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-title-limit"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
      ), document = exactTextTitle
    )
    assertTrue(validator.validate(titleSnapshot).isValid)
  }
}
