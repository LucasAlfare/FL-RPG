package com.lucasalfare.flrpg.communicationprotocol

class ResyncHandler {
  fun createResyncRequest(
    sessionId: SessionId,
    messageId: MessageId,
    sequence: Sequence,
    lastKnownSequence: Sequence? = null,
    lastKnownRevision: Revision? = null
  ): ClientResyncRequest {
    return ClientResyncRequest(
      metadata = MessageMetadata(
        messageId = messageId, sessionId = sessionId, sequence = sequence
      ), lastKnownSequence = lastKnownSequence, lastKnownRevision = lastKnownRevision
    )
  }

  fun createSnapshotResponse(
    request: ClientResyncRequest,
    replyMessageId: MessageId,
    serverSequence: Sequence,
    currentDocument: PresentationDocument
  ): PresentationSnapshot {
    return PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = replyMessageId,
        sessionId = request.sessionId,
        sequence = serverSequence,
        correlationId = CorrelationId(request.messageId)
      ), document = currentDocument
    )
  }

  fun applyResync(
    snapshot: PresentationSnapshot, sequenceTracker: SequenceTracker
  ): PresentationDocument {
    sequenceTracker.reset(snapshot.sequence.next())
    return snapshot.document
  }
}

fun PresentationDocument.applyResync(
  snapshot: PresentationSnapshot, sequenceTracker: SequenceTracker
): PresentationDocument {
  sequenceTracker.reset(snapshot.sequence.next())
  return snapshot.document
}