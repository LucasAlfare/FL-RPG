package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ClientAction")
data class ClientAction(
  override val metadata: MessageMetadata, val actionId: String, val parameters: Map<String, String> = emptyMap()
) : ClientMessage {
  init {
    require(actionId.isNotBlank()) { "Action ID must not be blank." }
  }
}

@Serializable
@SerialName("ClientSelection")
data class ClientSelection(
  override val metadata: MessageMetadata, val choiceId: String
) : ClientMessage {
  init {
    require(choiceId.isNotBlank()) { "Choice ID must not be blank." }
  }
}

@Serializable
@SerialName("ClientTextCommand")
data class ClientTextCommand(
  override val metadata: MessageMetadata, val rawText: String
) : ClientMessage {
  init {
    require(rawText.isNotBlank()) { "Raw text must not be blank." }
  }
}

@Serializable
@SerialName("ClientCancel")
data class ClientCancel(
  override val metadata: MessageMetadata, val targetMessageId: MessageId
) : ClientMessage

@Serializable
@SerialName("ClientResyncRequest")
data class ClientResyncRequest(
  override val metadata: MessageMetadata,
  val lastKnownSequence: Sequence? = null,
  val lastKnownRevision: Revision? = null
) : ClientMessage