package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProtocolFact(
  val name: String, val arguments: Map<String, String> = emptyMap()
) {
  init {
    require(name.isNotBlank()) { "Fact name must not be blank." }
  }
}

@Serializable
@SerialName("ServerAccepted")
data class ServerAccepted(
  override val metadata: MessageMetadata, val detail: String? = null
) : ServerMessage {
  init {
    require(metadata.correlationId != null) { "ServerAccepted must have a correlationId." }
  }
}

@Serializable
@SerialName("ServerRejected")
data class ServerRejected(
  override val metadata: MessageMetadata, val reason: String
) : ServerMessage {
  init {
    require(metadata.correlationId != null) { "ServerRejected must have a correlationId." }
    require(reason.isNotBlank()) { "Reason must not be blank." }
  }
}

@Serializable
@SerialName("ServerCompleted")
data class ServerCompleted(
  override val metadata: MessageMetadata,
  val facts: List<ProtocolFact> = emptyList(),
  @Deprecated("Use facts instead") val result: Map<String, String> = emptyMap()
) : ServerMessage {
  init {
    require(metadata.correlationId != null) { "ServerCompleted must have a correlationId." }
  }
}

@Serializable
@SerialName("ServerFailed")
data class ServerFailed(
  override val metadata: MessageMetadata, val error: String
) : ServerMessage {
  init {
    require(metadata.correlationId != null) { "ServerFailed must have a correlationId." }
    require(error.isNotBlank()) { "Error must not be blank." }
  }
}

@Serializable
@SerialName("ServerProtocolError")
data class ServerProtocolError(
  override val metadata: MessageMetadata, val code: String, val description: String
) : ServerMessage {
  init {
    require(code.isNotBlank()) { "Code must not be blank." }
    require(description.isNotBlank()) { "Description must not be blank." }
  }
}

@Serializable
@SerialName("ServerEvent")
data class ServerEvent(
  override val metadata: MessageMetadata, val type: String, val payload: Map<String, String> = emptyMap()
) : ServerMessage {
  init {
    require(type.isNotBlank()) { "Event type must not be blank." }
  }
}