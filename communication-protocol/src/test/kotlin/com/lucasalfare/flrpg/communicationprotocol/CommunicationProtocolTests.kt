package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.coroutines.*
import kotlin.test.*

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

  @Test
  fun protocolValidator_acceptsValidClientMessages() {
    val validator = ProtocolValidator()
    val metadata = MessageMetadata(
      messageId = MessageId("msg-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
    )

    val hello = ClientHello(metadata = metadata)
    assertTrue(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, hello).isValid)
    assertTrue(validator.validateSessionState(SessionState.NON_EXISTENT, hello).isValid)

    val action = ClientAction(metadata = metadata, actionId = "move", parameters = mapOf("dir" to "north"))
    assertTrue(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, action).isValid)
    assertTrue(validator.validateSessionState(SessionState.ESTABLISHED, action).isValid)

    val selection = ClientSelection(metadata = metadata, choiceId = "choice-1")
    assertTrue(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, selection).isValid)

    val textCommand = ClientTextCommand(metadata = metadata, rawText = "look")
    assertTrue(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, textCommand).isValid)

    val cancel = ClientCancel(metadata = metadata, targetMessageId = MessageId("target-1"))
    assertTrue(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, cancel).isValid)

    val resync = ClientResyncRequest(metadata = metadata)
    assertTrue(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, resync).isValid)
  }

  @Test
  fun protocolValidator_rejectsWrongMessageDirection() {
    val validator = ProtocolValidator()
    val metadata = MessageMetadata(
      messageId = MessageId("msg-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
    )

    val clientAction = ClientAction(metadata = metadata, actionId = "move")
    val clientDirectionResult = validator.validateDirection(MessageDirection.SERVER_TO_CLIENT, clientAction)
    assertFalse(clientDirectionResult.isValid)

    val serverWelcome = ServerWelcome(metadata = metadata)
    val serverDirectionResult = validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, serverWelcome)
    assertFalse(serverDirectionResult.isValid)

    val serverEvent = ServerEvent(metadata = metadata, type = "notification")
    assertFalse(validator.validateDirection(MessageDirection.CLIENT_TO_SERVER, serverEvent).isValid)
  }

  @Test
  fun protocolValidator_rejectsUnknownCorrelation() {
    val validator = ProtocolValidator()
    val pendingIds = setOf(MessageId("known-request"))
    val metadataWithUnknown = MessageMetadata(
      messageId = MessageId("response-1"),
      sessionId = SessionId("session-1"),
      sequence = Sequence.ZERO,
      correlationId = CorrelationId("unknown-request")
    )

    val accepted = ServerAccepted(metadata = metadataWithUnknown)
    val acceptedResult = validator.validateCorrelation(accepted, pendingIds)
    assertFalse(acceptedResult.isValid)

    val rejected = ServerRejected(metadata = metadataWithUnknown, reason = "denied")
    assertFalse(validator.validateCorrelation(rejected, pendingIds).isValid)

    val completed = ServerCompleted(metadata = metadataWithUnknown)
    assertFalse(validator.validateCorrelation(completed, pendingIds).isValid)

    val failed = ServerFailed(metadata = metadataWithUnknown, error = "error")
    assertFalse(validator.validateCorrelation(failed, pendingIds).isValid)

    val metadataWithKnown = MessageMetadata(
      messageId = MessageId("response-2"),
      sessionId = SessionId("session-1"),
      sequence = Sequence(1),
      correlationId = CorrelationId("known-request")
    )
    val acceptedKnown = ServerAccepted(metadata = metadataWithKnown)
    assertTrue(validator.validateCorrelation(acceptedKnown, pendingIds).isValid)
  }

  @Test
  fun protocolValidator_handlesMissingRequiredFields() {
    val validator = ProtocolValidator()
    val metadataWithoutCorrelation = MessageMetadata(
      messageId = MessageId("response-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
    )

    var acceptedThrew = false
    try {
      ServerAccepted(metadata = metadataWithoutCorrelation)
    } catch (_: IllegalArgumentException) {
      acceptedThrew = true
    }
    assertTrue(acceptedThrew)

    var rejectedThrew = false
    try {
      ServerRejected(metadata = metadataWithoutCorrelation, reason = "denied")
    } catch (_: IllegalArgumentException) {
      rejectedThrew = true
    }
    assertTrue(rejectedThrew)

    var completedThrew = false
    try {
      ServerCompleted(metadata = metadataWithoutCorrelation)
    } catch (_: IllegalArgumentException) {
      completedThrew = true
    }
    assertTrue(completedThrew)

    var failedThrew = false
    try {
      ServerFailed(metadata = metadataWithoutCorrelation, error = "error")
    } catch (_: IllegalArgumentException) {
      failedThrew = true
    }
    assertTrue(failedThrew)

    val metadataWithCorrelation = MessageMetadata(
      messageId = MessageId("response-2"),
      sessionId = SessionId("session-1"),
      sequence = Sequence.ZERO,
      correlationId = CorrelationId("req-1")
    )
    assertTrue(validator.validateCorrelation(ServerAccepted(metadata = metadataWithCorrelation)).isValid)
    assertTrue(
      validator.validateCorrelation(
        ServerRejected(
          metadata = metadataWithCorrelation, reason = "denied"
        )
      ).isValid
    )
    assertTrue(validator.validateCorrelation(ServerCompleted(metadata = metadataWithCorrelation)).isValid)
    assertTrue(validator.validateCorrelation(ServerFailed(metadata = metadataWithCorrelation, error = "error")).isValid)
  }

  @Test
  fun protocolMessages_roundTripThroughJson() {
    val metadata = MessageMetadata(
      messageId = MessageId("msg-1"),
      sessionId = SessionId("session-1"),
      sequence = Sequence(5),
      correlationId = CorrelationId("corr-1"),
      version = ProtocolVersion(1, 2),
      deliveryMode = DeliveryMode.RELIABLE_UNORDERED
    )

    val hello = ClientHello(metadata = metadata, resumeSessionId = SessionId("resume-1"))
    val helloJson = hello.toJson()
    val helloRestored = helloJson.toProtocolMessage<ClientHello>()
    assertEquals(hello, helloRestored)

    val action = ClientAction(metadata = metadata, actionId = "move", parameters = mapOf("dir" to "north"))
    val actionJson = action.toJson()
    val actionRestored = actionJson.toProtocolMessage<ClientAction>()
    assertEquals(action, actionRestored)

    val selection = ClientSelection(metadata = metadata, choiceId = "c1")
    assertEquals(selection, selection.toJson().toProtocolMessage<ClientSelection>())

    val textCommand = ClientTextCommand(metadata = metadata, rawText = "look around")
    assertEquals(textCommand, textCommand.toJson().toProtocolMessage<ClientTextCommand>())

    val cancel = ClientCancel(metadata = metadata, targetMessageId = MessageId("target-1"))
    assertEquals(cancel, cancel.toJson().toProtocolMessage<ClientCancel>())

    val resync = ClientResyncRequest(
      metadata = metadata, lastKnownSequence = Sequence(3), lastKnownRevision = Revision(2)
    )
    assertEquals(resync, resync.toJson().toProtocolMessage<ClientResyncRequest>())

    val welcome = ServerWelcome(metadata = metadata, isResumed = true)
    assertEquals(welcome, welcome.toJson().toProtocolMessage<ServerWelcome>())

    val reject = ServerReject(metadata = metadata, reason = RejectionReason.INCOMPATIBLE_VERSION)
    assertEquals(reject, reject.toJson().toProtocolMessage<ServerReject>())

    val accepted = ServerAccepted(metadata = metadata, detail = "ok")
    assertEquals(accepted, accepted.toJson().toProtocolMessage<ServerAccepted>())

    val rejected = ServerRejected(metadata = metadata, reason = "no")
    assertEquals(rejected, rejected.toJson().toProtocolMessage<ServerRejected>())

    val completed = ServerCompleted(
      metadata = metadata, facts = listOf(ProtocolFact(name = "moved", arguments = mapOf("to" to "room-1")))
    )
    assertEquals(completed, completed.toJson().toProtocolMessage<ServerCompleted>())

    val failed = ServerFailed(metadata = metadata, error = "fail")
    assertEquals(failed, failed.toJson().toProtocolMessage<ServerFailed>())

    val protocolError = ServerProtocolError(metadata = metadata, code = "E1", description = "bad")
    assertEquals(protocolError, protocolError.toJson().toProtocolMessage<ServerProtocolError>())

    val event = ServerEvent(metadata = metadata, type = "tick", payload = mapOf("t" to "1"))
    assertEquals(event, event.toJson().toProtocolMessage<ServerEvent>())

    val document = PresentationDocument(
      revision = Revision(1), blocks = listOf(
        PresentationBlock(
          id = BlockId("b1"),
          content = BlockContent.Text("hello", TextStyle.HEADER),
          title = "Title",
          region = PresentationRegion.SIDEBAR
        ), PresentationBlock(
          id = BlockId("b2"), content = BlockContent.Choices(
            choices = listOf(Choice(id = "c1", text = "Go", shortcut = "1", enabled = true))
          )
        ), PresentationBlock(
          id = BlockId("b3"), content = BlockContent.ItemsList(items = listOf("a", "b"), isOrdered = true)
        ), PresentationBlock(
          id = BlockId("b4"), content = BlockContent.Feed(
            feedId = "f1",
            items = listOf(FeedItem(id = "i1", text = "item", style = TextStyle.MUTED)),
            ordering = FeedOrdering.REVERSE_CHRONOLOGICAL
          )
        )
      )
    )
    val snapshot = PresentationSnapshot(metadata = metadata, document = document)
    assertEquals(snapshot, snapshot.toJson().toProtocolMessage<PresentationSnapshot>())

    val patch = PresentationPatch(
      metadata = metadata, baseRevision = Revision.ZERO, newRevision = Revision(1), operations = listOf(
        PatchOperation.Upsert(PresentationBlock(id = BlockId("u1"), content = BlockContent.Text("up"))),
        PatchOperation.Remove(BlockId("r1")),
        PatchOperation.Append(PresentationBlock(id = BlockId("a1"), content = BlockContent.Text("ap"))),
        PatchOperation.Clear
      )
    )
    assertEquals(patch, patch.toJson().toProtocolMessage<PresentationPatch>())
  }

  @Test
  fun protocolValidator_rejectsClientHelloForEstablishedSession() {
    val validator = ProtocolValidator()
    val metadata = MessageMetadata(
      messageId = MessageId("hello-1"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
    )
    val hello = ClientHello(metadata = metadata)

    val establishedResult = validator.validateSessionState(SessionState.ESTABLISHED, hello)
    assertFalse(establishedResult.isValid)

    val resumedResult = validator.validateSessionState(SessionState.RESUMED, hello)
    assertFalse(resumedResult.isValid)

    val nonExistentResult = validator.validateSessionState(SessionState.NON_EXISTENT, hello)
    assertTrue(nonExistentResult.isValid)
  }

  @Test
  fun protocolMessages_preserveOptionalFields() {
    val baseMetadata = MessageMetadata(
      messageId = MessageId("msg-opt"), sessionId = SessionId("session-1"), sequence = Sequence.ZERO
    )

    val helloWithoutResume = ClientHello(metadata = baseMetadata)
    val restoredHello = helloWithoutResume.toJson().toProtocolMessage<ClientHello>()
    assertEquals(null, restoredHello.resumeSessionId)

    val helloWithResume = ClientHello(metadata = baseMetadata, resumeSessionId = SessionId("resume-42"))
    assertEquals(SessionId("resume-42"), helloWithResume.toJson().toProtocolMessage<ClientHello>().resumeSessionId)

    val actionEmptyParams = ClientAction(metadata = baseMetadata, actionId = "act")
    assertEquals(emptyMap(), actionEmptyParams.toJson().toProtocolMessage<ClientAction>().parameters)

    val resyncEmpty = ClientResyncRequest(metadata = baseMetadata)
    val restoredResync = resyncEmpty.toJson().toProtocolMessage<ClientResyncRequest>()
    assertEquals(null, restoredResync.lastKnownSequence)
    assertEquals(null, restoredResync.lastKnownRevision)

    val acceptedNoDetail = ServerAccepted(metadata = baseMetadata.copy(correlationId = CorrelationId("c1")))
    assertEquals(null, acceptedNoDetail.toJson().toProtocolMessage<ServerAccepted>().detail)

    val completedNoFacts = ServerCompleted(metadata = baseMetadata.copy(correlationId = CorrelationId("c2")))
    val restoredCompleted = completedNoFacts.toJson().toProtocolMessage<ServerCompleted>()
    assertEquals(emptyList(), restoredCompleted.facts)

    val choiceNoShortcut = Choice(id = "c1", text = "Option")
    val choicesBlock = PresentationBlock(
      id = BlockId("choices"), content = BlockContent.Choices(choices = listOf(choiceNoShortcut))
    )
    val doc = PresentationDocument(revision = Revision.ZERO, blocks = listOf(choicesBlock))
    val snapshot = PresentationSnapshot(metadata = baseMetadata, document = doc)
    val restoredChoice = snapshot.toJson()
      .toProtocolMessage<PresentationSnapshot>().document.blocks.first().content as BlockContent.Choices
    assertEquals(null, restoredChoice.choices.first().shortcut)
    assertTrue(restoredChoice.choices.first().enabled)
  }

  @Test
  fun protocolMessages_rejectMalformedJson() {
    val invalidJsonSamples = listOf(
      "", "{", "null", "[]", """{"type":"UnknownMessage"}""", """{"metadata":{}}""", """{"messageId":"x"}"""
    )

    for (sample in invalidJsonSamples) {
      var threw = false
      try {
        sample.toProtocolMessage<ProtocolMessage>()
      } catch (_: Exception) {
        threw = true
      }
      assertTrue(threw, "Expected malformed JSON to fail: $sample")
    }
  }

  @Test
  fun presentationSnapshot_replacesPreviousDocument() {
    val previous = PresentationDocument(
      revision = Revision(3), blocks = listOf(
        PresentationBlock(id = BlockId("old"), content = BlockContent.Text("old content"))
      )
    )
    val newDocument = PresentationDocument(
      revision = Revision(9), blocks = listOf(
        PresentationBlock(id = BlockId("new"), content = BlockContent.Text("new content"))
      )
    )
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-1"), sessionId = SessionId("session-1"), sequence = Sequence(4)
      ), document = newDocument
    )

    val result = previous.applySnapshot(snapshot)

    assertEquals(newDocument, result)
    assertEquals(Revision(9), result.revision)
    assertEquals(1, result.blocks.size)
    assertEquals(BlockId("new"), result.blocks.first().id)
    assertEquals(Revision(3), previous.revision)
    assertEquals(1, previous.blocks.size)
    assertEquals(BlockId("old"), previous.blocks.first().id)
  }

  @Test
  fun applyPatch_appliesValidUpsert() {
    val original = PresentationDocument(
      revision = Revision(1), blocks = listOf(
        PresentationBlock(id = BlockId("a"), content = BlockContent.Text("original-a")),
        PresentationBlock(id = BlockId("b"), content = BlockContent.Text("original-b"))
      )
    )
    val updatedBlock = PresentationBlock(id = BlockId("a"), content = BlockContent.Text("updated-a"))
    val insertedBlock = PresentationBlock(id = BlockId("c"), content = BlockContent.Text("new-c"))
    val patch = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-upsert"), sessionId = SessionId("session-1"), sequence = Sequence(2)
      ), baseRevision = Revision(1), newRevision = Revision(2), operations = listOf(
        PatchOperation.Upsert(updatedBlock), PatchOperation.Upsert(insertedBlock)
      )
    )

    val result = original.applyPatch(patch)

    assertEquals(Revision(2), result.revision)
    assertEquals(3, result.blocks.size)
    assertEquals(updatedBlock, result.blocks.first { it.id == BlockId("a") })
    assertEquals(insertedBlock, result.blocks.first { it.id == BlockId("c") })
    assertEquals(Revision(1), original.revision)
    assertEquals(2, original.blocks.size)
  }

  @Test
  fun applyPatch_appliesValidRemove() {
    val original = PresentationDocument(
      revision = Revision(1), blocks = listOf(
        PresentationBlock(id = BlockId("a"), content = BlockContent.Text("a")),
        PresentationBlock(id = BlockId("b"), content = BlockContent.Text("b"))
      )
    )
    val patch = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-remove"), sessionId = SessionId("session-1"), sequence = Sequence(2)
      ), baseRevision = Revision(1), newRevision = Revision(2), operations = listOf(PatchOperation.Remove(BlockId("a")))
    )

    val result = original.applyPatch(patch)

    assertEquals(Revision(2), result.revision)
    assertEquals(1, result.blocks.size)
    assertEquals(BlockId("b"), result.blocks.first().id)
    assertEquals(Revision(1), original.revision)
    assertEquals(2, original.blocks.size)
  }

  @Test
  fun applyPatch_appliesValidAppend() {
    val original = PresentationDocument(
      revision = Revision(1), blocks = listOf(PresentationBlock(id = BlockId("a"), content = BlockContent.Text("a")))
    )
    val appendedBlock = PresentationBlock(id = BlockId("b"), content = BlockContent.Text("b"))
    val patch = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-append"), sessionId = SessionId("session-1"), sequence = Sequence(2)
      ),
      baseRevision = Revision(1),
      newRevision = Revision(2),
      operations = listOf(PatchOperation.Append(appendedBlock))
    )

    val result = original.applyPatch(patch)

    assertEquals(Revision(2), result.revision)
    assertEquals(2, result.blocks.size)
    assertEquals(BlockId("a"), result.blocks[0].id)
    assertEquals(appendedBlock, result.blocks[1])
    assertEquals(Revision(1), original.revision)
    assertEquals(1, original.blocks.size)
  }

  @Test
  fun applyPatch_rejectsInvalidPatch() {
    val original = PresentationDocument(
      revision = Revision(5), blocks = listOf(PresentationBlock(id = BlockId("a"), content = BlockContent.Text("a")))
    )

    val wrongBaseRevision = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-wrong-rev"), sessionId = SessionId("session-1"), sequence = Sequence(2)
      ), baseRevision = Revision(4), newRevision = Revision(6), operations = listOf(PatchOperation.Clear)
    )
    assertFailsWith<IllegalArgumentException> { original.applyPatch(wrongBaseRevision) }

    val removeMissing = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-remove-missing"), sessionId = SessionId("session-1"), sequence = Sequence(3)
      ),
      baseRevision = Revision(5),
      newRevision = Revision(6),
      operations = listOf(PatchOperation.Remove(BlockId("missing")))
    )
    assertFailsWith<IllegalArgumentException> { original.applyPatch(removeMissing) }

    val appendDuplicate = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-append-dup"), sessionId = SessionId("session-1"), sequence = Sequence(4)
      ), baseRevision = Revision(5), newRevision = Revision(6), operations = listOf(
        PatchOperation.Append(
          PresentationBlock(id = BlockId("a"), content = BlockContent.Text("dup"))
        )
      )
    )
    assertFailsWith<IllegalArgumentException> { original.applyPatch(appendDuplicate) }

    assertEquals(Revision(5), original.revision)
    assertEquals(1, original.blocks.size)
    assertEquals(BlockId("a"), original.blocks.first().id)
  }

  @Test
  fun applyPatch_preservesOriginalDocumentOnFailure() {
    val original = PresentationDocument(
      revision = Revision(1), blocks = listOf(
        PresentationBlock(id = BlockId("a"), content = BlockContent.Text("a")),
        PresentationBlock(id = BlockId("b"), content = BlockContent.Text("b"))
      )
    )
    val originalBlocks = original.blocks.toList()

    val patch = PresentationPatch(
      metadata = MessageMetadata(
        messageId = MessageId("patch-partial"), sessionId = SessionId("session-1"), sequence = Sequence(2)
      ), baseRevision = Revision(1), newRevision = Revision(2), operations = listOf(
        PatchOperation.Upsert(
          PresentationBlock(id = BlockId("a"), content = BlockContent.Text("modified"))
        ), PatchOperation.Remove(BlockId("does-not-exist"))
      )
    )

    assertFailsWith<IllegalArgumentException> { original.applyPatch(patch) }

    assertEquals(Revision(1), original.revision)
    assertEquals(originalBlocks, original.blocks)
    assertEquals(2, original.blocks.size)
    assertEquals(BlockContent.Text("a"), original.blocks.first { it.id == BlockId("a") }.content)
  }

  @Test
  fun resyncHandler_appliesSnapshot() {
    val handler = ResyncHandler()
    val snapshotDocument = PresentationDocument(
      revision = Revision(7), blocks = listOf(
        PresentationBlock(id = BlockId("main"), content = BlockContent.Text("restored"))
      )
    )
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-resync"), sessionId = SessionId("session-1"), sequence = Sequence(9)
      ), document = snapshotDocument
    )
    val currentDocument = PresentationDocument(
      revision = Revision(2), blocks = listOf(
        PresentationBlock(id = BlockId("stale"), content = BlockContent.Text("stale"))
      )
    )
    val tracker = SequenceTracker(Sequence(1))

    val result = handler.applyResync(snapshot, tracker)

    assertEquals(snapshotDocument, result)
    assertEquals(Revision(7), result.revision)
    assertEquals(1, result.blocks.size)
    assertEquals(BlockId("main"), result.blocks.first().id)
    assertEquals(Revision(2), currentDocument.revision)
  }

  @Test
  fun resyncHandler_resetsSequenceState() {
    val handler = ResyncHandler()
    val tracker = SequenceTracker(Sequence(1))
    val processedId = MessageId("processed-before-resync")
    tracker.process(processedId, Sequence(1))
    assertTrue(tracker.isProcessed(processedId))
    assertEquals(Sequence(2), tracker.expectedSequence)

    val snapshotDocument = PresentationDocument(
      revision = Revision(3), blocks = listOf(
        PresentationBlock(id = BlockId("main"), content = BlockContent.Text("after resync"))
      )
    )
    val snapshot = PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId("snap-resync"), sessionId = SessionId("session-1"), sequence = Sequence(20)
      ), document = snapshotDocument
    )

    handler.applyResync(snapshot, tracker)

    assertEquals(Sequence(21), tracker.expectedSequence)
    assertFalse(tracker.isProcessed(processedId))
  }

  @Test
  fun sequenceTracker_concurrentSameSequenceAcceptsOnlyOne() = runBlocking {
    val tracker = SequenceTracker(Sequence.ZERO)
    val gate = CompletableDeferred<Unit>()
    val contenders = 64

    val jobs = (1..contenders).map { index ->
      async(Dispatchers.Default) {
        gate.await()
        tracker.process(MessageId("concurrent-$index"), Sequence.ZERO)
      }
    }

    gate.complete(Unit)
    val statuses = jobs.awaitAll()

    assertEquals(1, statuses.count { it == SequenceStatus.EXPECTED })
    assertEquals(contenders - 1, statuses.count { it == SequenceStatus.STALE })
    assertEquals(Sequence(1), tracker.expectedSequence)
    assertEquals(1, (1..contenders).count { tracker.isProcessed(MessageId("concurrent-$it")) })
  }

  @Test
  fun sequenceTracker_concurrentMessagesPreserveSequenceState() = runBlocking {
    val tracker = SequenceTracker(Sequence.ZERO)
    val gate = CompletableDeferred<Unit>()
    val messageCount = 64

    val jobs = (0 until messageCount).map { index ->
      async(Dispatchers.Default) {
        gate.await()
        val messageId = MessageId("message-$index")
        val status = tracker.process(messageId, Sequence(index.toLong()))
        index to status
      }
    }

    gate.complete(Unit)
    val outcomes = jobs.awaitAll()

    val acceptedIndices = outcomes.filter { it.second == SequenceStatus.EXPECTED }.map { it.first }.toSet()
    val processedIndices = (0 until messageCount).filter { tracker.isProcessed(MessageId("message-$it")) }.toSet()

    assertTrue(acceptedIndices.isNotEmpty())
    assertEquals(acceptedIndices, processedIndices)
    assertEquals(acceptedIndices.size.toLong(), tracker.expectedSequence.value)
    assertTrue(outcomes.filter { it.second != SequenceStatus.EXPECTED }
      .all { it.second == SequenceStatus.GAP || it.second == SequenceStatus.STALE })
  }
}