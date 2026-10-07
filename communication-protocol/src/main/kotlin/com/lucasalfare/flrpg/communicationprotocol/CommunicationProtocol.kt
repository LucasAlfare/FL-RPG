@file:Suppress("unused")

package com.lucasalfare.flrpg.communicationprotocol

data class ProtocolVersion(val major: Int = 1, val minor: Int = 0) {
  init {
    require(major >= 0 && minor >= 0) { "The protocol's version must have non-negative components" }
  }

  fun isCompatibleWith(other: ProtocolVersion): Boolean = this.major == other.major

  override fun toString(): String = "$major.$minor"

  companion object {
    val V1 = ProtocolVersion(1, 0)
  }
}

@JvmInline
value class MessageId(val value: String) {
  init {
    require(value.isNotBlank()) { "MessageId must not be blank." }
  }
}

@JvmInline
value class SessionId(val value: String) {
  init {
    require(value.isNotBlank()) { "SessionId must not be blank." }
  }
}

@JvmInline
value class CorrelationId(val value: String) {
  init {
    require(value.isNotBlank()) { "CorrelationId must not be blank." }
  }

  constructor(messageId: MessageId) : this(messageId.value)
}

@JvmInline
value class Sequence(val value: Long) : Comparable<Sequence> {
  init {
    require(value >= 0L) { "Sequence must not be negative, but was $value." }
  }

  fun next(): Sequence = Sequence(value + 1)

  override fun compareTo(other: Sequence): Int = value.compareTo(other.value)

  companion object {
    val ZERO = Sequence(0L)
  }
}

@JvmInline
value class Revision(val value: Long) : Comparable<Revision> {
  init {
    require(value >= 0L) { "Revision must not be negative, but was $value." }
  }

  fun next(): Revision = Revision(value + 1)

  override fun compareTo(other: Revision): Int = value.compareTo(other.value)

  companion object {
    val ZERO = Revision(0L)
  }
}

data class MessageMetadata(
  val messageId: MessageId,
  val sessionId: SessionId,
  val sequence: Sequence,
  val correlationId: CorrelationId? = null,
  val version: ProtocolVersion = ProtocolVersion.V1
)

sealed interface ProtocolMessage {
  val metadata: MessageMetadata

  val messageId: MessageId get() = metadata.messageId
  val sessionId: SessionId get() = metadata.sessionId
  val sequence: Sequence get() = metadata.sequence
  val correlationId: CorrelationId? get() = metadata.correlationId
  val version: ProtocolVersion get() = metadata.version
}

sealed interface ClientMessage : ProtocolMessage

sealed interface ServerMessage : ProtocolMessage

enum class SessionState {
  NON_EXISTENT, ESTABLISHED, REJECTED, RESUMED
}

enum class RejectionReason {
  INCOMPATIBLE_VERSION, SESSION_NOT_FOUND, SERVER_FULL, GENERIC_ERROR
}

data class ClientHello(
  override val metadata: MessageMetadata, val resumeSessionId: SessionId? = null
) : ClientMessage

data class ServerWelcome(
  override val metadata: MessageMetadata, val isResumed: Boolean = false
) : ServerMessage

data class ServerReject(
  override val metadata: MessageMetadata, val reason: RejectionReason
) : ServerMessage

class SessionLifecycleHandler(
  val serverVersion: ProtocolVersion = ProtocolVersion.V1
) {
  private val activeSessions = mutableSetOf<SessionId>()

  fun processHello(hello: ClientHello, replyMessageId: MessageId): Pair<SessionState, ServerMessage> {
    if (!hello.version.isCompatibleWith(serverVersion)) {
      val response = ServerReject(
        metadata = MessageMetadata(
          messageId = replyMessageId,
          sessionId = hello.sessionId,
          sequence = Sequence.ZERO,
          correlationId = CorrelationId(hello.messageId),
          version = serverVersion
        ), reason = RejectionReason.INCOMPATIBLE_VERSION
      )
      return SessionState.REJECTED to response
    }

    return if (hello.resumeSessionId != null) {
      if (activeSessions.contains(hello.resumeSessionId)) {
        val response = ServerWelcome(
          metadata = MessageMetadata(
            messageId = replyMessageId,
            sessionId = hello.resumeSessionId,
            sequence = Sequence.ZERO,
            correlationId = CorrelationId(hello.messageId),
            version = serverVersion
          ), isResumed = true
        )
        SessionState.RESUMED to response
      } else {
        val response = ServerReject(
          metadata = MessageMetadata(
            messageId = replyMessageId,
            sessionId = hello.sessionId,
            sequence = Sequence.ZERO,
            correlationId = CorrelationId(hello.messageId),
            version = serverVersion
          ), reason = RejectionReason.SESSION_NOT_FOUND
        )
        SessionState.REJECTED to response
      }
    } else {
      activeSessions.add(hello.sessionId)
      val response = ServerWelcome(
        metadata = MessageMetadata(
          messageId = replyMessageId,
          sessionId = hello.sessionId,
          sequence = Sequence.ZERO,
          correlationId = CorrelationId(hello.messageId),
          version = serverVersion
        ), isResumed = false
      )
      SessionState.ESTABLISHED to response
    }
  }

  fun isSessionActive(sessionId: SessionId): Boolean = activeSessions.contains(sessionId)
}

data class ClientAction(
  override val metadata: MessageMetadata, val actionId: String, val parameters: Map<String, String> = emptyMap()
) : ClientMessage {
  init {
    require(actionId.isNotBlank()) { "Action ID must not be blank." }
  }
}

data class ClientSelection(
  override val metadata: MessageMetadata, val choiceId: String
) : ClientMessage {
  init {
    require(choiceId.isNotBlank()) { "Choice ID must not be blank." }
  }
}

data class ClientTextCommand(
  override val metadata: MessageMetadata, val rawText: String
) : ClientMessage {
  init {
    require(rawText.isNotBlank()) { "Raw text must not be blank." }
  }
}

data class ClientCancel(
  override val metadata: MessageMetadata, val targetMessageId: MessageId
) : ClientMessage

data class ClientResyncRequest(
  override val metadata: MessageMetadata,
  val lastKnownSequence: Sequence? = null,
  val lastKnownRevision: Revision? = null
) : ClientMessage