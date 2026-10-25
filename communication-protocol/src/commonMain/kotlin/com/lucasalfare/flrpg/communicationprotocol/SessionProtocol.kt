package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionState {
  NON_EXISTENT, ESTABLISHED, REJECTED, RESUMED
}

@Serializable
enum class RejectionReason {
  INCOMPATIBLE_VERSION, SESSION_NOT_FOUND, SERVER_FULL, GENERIC_ERROR
}

@Serializable
@SerialName("ClientHello")
data class ClientHello(
  override val metadata: MessageMetadata, val resumeSessionId: SessionId? = null
) : ClientMessage

@Serializable
@SerialName("ServerWelcome")
data class ServerWelcome(
  override val metadata: MessageMetadata, val isResumed: Boolean = false
) : ServerMessage

@Serializable
@SerialName("ServerReject")
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