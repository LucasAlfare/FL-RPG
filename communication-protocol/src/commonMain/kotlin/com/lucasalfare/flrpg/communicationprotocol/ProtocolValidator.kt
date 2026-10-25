package com.lucasalfare.flrpg.communicationprotocol

class ProtocolValidator {

  fun validateDirection(
    expectedDirection: MessageDirection, message: ProtocolMessage
  ): ValidationResult {
    return when (expectedDirection) {
      MessageDirection.CLIENT_TO_SERVER -> {
        if (message is ClientMessage) ValidationResult.Valid
        else invalid("Message '${message::class.simpleName}' is invalid for direction CLIENT_TO_SERVER.")
      }

      MessageDirection.SERVER_TO_CLIENT -> {
        if (message is ServerMessage) ValidationResult.Valid
        else invalid("Message '${message::class.simpleName}' is invalid for direction SERVER_TO_CLIENT.")
      }
    }
  }

  fun validateSessionState(
    sessionState: SessionState, message: ProtocolMessage
  ): ValidationResult {
    return when (sessionState) {
      SessionState.NON_EXISTENT -> {
        if (message is ClientHello || message is ServerWelcome || message is ServerReject) {
          ValidationResult.Valid
        } else {
          invalid("Message '${message::class.simpleName}' is invalid for NON_EXISTENT session state.")
        }
      }

      SessionState.ESTABLISHED, SessionState.RESUMED -> {
        if (message is ClientHello) {
          invalid("ClientHello is invalid when session is already ${sessionState.name}.")
        } else {
          ValidationResult.Valid
        }
      }

      SessionState.REJECTED -> {
        invalid("No messages allowed for REJECTED session state.")
      }
    }
  }

  fun validateCorrelation(
    message: ProtocolMessage, activePendingRequestIds: Set<MessageId>? = null
  ): ValidationResult {
    val correlationId = message.correlationId
    if (message is ServerAccepted || message is ServerRejected || message is ServerCompleted || message is ServerFailed) {
      if (correlationId == null) {
        return invalid("Response message '${message::class.simpleName}' missing required correlationId.")
      }
      if (activePendingRequestIds != null && MessageId(correlationId.value) !in activePendingRequestIds) {
        return invalid("Unmatched correlationId '${correlationId.value}'. No active request found.")
      }
    }
    return ValidationResult.Valid
  }

  fun validateRevision(
    currentDocRevision: Revision, patchBaseRevision: Revision
  ): ValidationResult {
    return if (currentDocRevision == patchBaseRevision) {
      ValidationResult.Valid
    } else {
      invalid("Revision mismatch: current document revision is ${currentDocRevision.value}, but patch base revision is ${patchBaseRevision.value}.")
    }
  }

  fun validateSequence(
    tracker: SequenceTracker, message: ProtocolMessage
  ): ValidationResult {
    val status = tracker.process(message.messageId, message.sequence)
    return when (status) {
      SequenceStatus.EXPECTED -> ValidationResult.Valid
      SequenceStatus.DUPLICATE -> invalid("Duplicate sequence or messageId: messageId '${message.messageId.value}', sequence ${message.sequence.value}.")
      SequenceStatus.GAP -> invalid("Sequence gap detected: expected ${tracker.expectedSequence.value}, received ${message.sequence.value}.")
      SequenceStatus.STALE -> invalid("Stale or out-of-order sequence: expected ${tracker.expectedSequence.value}, received ${message.sequence.value}.")
      SequenceStatus.OUT_OF_ORDER -> invalid("Out-of-order sequence received: sequence ${message.sequence.value}.")
    }
  }

  private fun invalid(message: String) = ValidationResult.Invalid(ValidationError.Protocol(message))
}