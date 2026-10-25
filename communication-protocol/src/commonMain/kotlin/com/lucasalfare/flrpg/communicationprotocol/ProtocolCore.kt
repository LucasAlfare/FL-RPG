package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
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

@Serializable
@JvmInline
value class MessageId(val value: String) {
  init {
    require(value.isNotBlank()) { "MessageId must not be blank." }
  }
}

@Serializable
@JvmInline
value class SessionId(val value: String) {
  init {
    require(value.isNotBlank()) { "SessionId must not be blank." }
  }
}

@Serializable
@JvmInline
value class CorrelationId(val value: String) {
  init {
    require(value.isNotBlank()) { "CorrelationId must not be blank." }
  }
}

fun CorrelationId(messageId: MessageId): CorrelationId = CorrelationId(messageId.value)

@Serializable
@JvmInline
value class Sequence(val value: Long) : Comparable<Sequence> {
  init {
    require(value >= 0L) { "Sequence must not be negative, but was $value." }
  }

  fun next(): Sequence = Sequence(if (value == Long.MAX_VALUE) 0L else value + 1)

  fun distanceTo(other: Sequence): Long {
    return if (other.value >= this.value) {
      other.value - this.value
    } else {
      (Long.MAX_VALUE - this.value + 1) + other.value
    }
  }

  override fun compareTo(other: Sequence): Int = value.compareTo(other.value)

  companion object {
    val ZERO = Sequence(0L)
    internal const val HALF_MAX_VALUE: Long = Long.MAX_VALUE / 2
  }
}

@Serializable
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

@Serializable
enum class DeliveryMode {
  RELIABLE_ORDERED, RELIABLE_UNORDERED, UNRELIABLE
}

@Serializable
enum class MessageDirection {
  CLIENT_TO_SERVER, SERVER_TO_CLIENT
}

@Serializable
data class MessageMetadata(
  val messageId: MessageId,
  val sessionId: SessionId,
  val sequence: Sequence,
  val correlationId: CorrelationId? = null,
  val version: ProtocolVersion = ProtocolVersion.V1,
  val deliveryMode: DeliveryMode = DeliveryMode.RELIABLE_ORDERED
)

@Serializable
sealed interface ProtocolMessage {
  val metadata: MessageMetadata

  val messageId: MessageId get() = metadata.messageId
  val sessionId: SessionId get() = metadata.sessionId
  val sequence: Sequence get() = metadata.sequence
  val correlationId: CorrelationId? get() = metadata.correlationId
  val version: ProtocolVersion get() = metadata.version
  val deliveryMode: DeliveryMode get() = metadata.deliveryMode
}

@Serializable
sealed interface ClientMessage : ProtocolMessage

@Serializable
sealed interface ServerMessage : ProtocolMessage
