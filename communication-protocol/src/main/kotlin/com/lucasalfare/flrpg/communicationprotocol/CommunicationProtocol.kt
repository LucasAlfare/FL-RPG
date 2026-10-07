@file:Suppress("unused")

package com.lucasalfare.flrpg.communicationprotocol

data class ProtocolVersion(val major: Int = 1, val minor: Int = 0) {
  init {
    require(major >= 0 && minor >= 0) { "The protocol's version must have non-negative components" }
  }

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