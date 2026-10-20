package com.lucasalfare.flrpg.communicationprotocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class BlockId(val value: String) {
  init {
    require(value.isNotBlank()) { "BlockId must not be blank." }
  }
}

@Serializable
enum class TextStyle {
  NORMAL, HEADER, SUBHEADER, HIGHLIGHT, MUTED, WARNING, ERROR
}

@Serializable
data class Choice(
  val id: String, val text: String, val shortcut: String? = null, val enabled: Boolean = true
) {
  init {
    require(id.isNotBlank()) { "Choice ID must not be blank." }
    require(text.isNotBlank()) { "Choice text must not be blank." }
    require(shortcut == null || shortcut.isNotBlank()) {
      "Choice shortcut cannot be blank when supplied."
    }
  }
}

@Serializable
enum class PresentationRegion {
  MAIN, SIDEBAR, HISTORY
}

@Serializable
enum class FeedOrdering {
  CHRONOLOGICAL, REVERSE_CHRONOLOGICAL
}

@Serializable
data class FeedItem(
  val id: String, val text: String, val style: TextStyle = TextStyle.NORMAL
) {
  init {
    require(id.isNotBlank()) { "Feed item ID must not be blank." }
  }
}

@Serializable
sealed interface BlockContent {
  @Serializable
  @SerialName("Text")
  data class Text(
    val text: String, val style: TextStyle = TextStyle.NORMAL
  ) : BlockContent

  @Serializable
  @SerialName("ItemsList")
  data class ItemsList(
    val items: List<String>, val isOrdered: Boolean = false
  ) : BlockContent

  @Serializable
  @SerialName("Choices")
  data class Choices(
    val choices: List<Choice>
  ) : BlockContent

  @Serializable
  @SerialName("Activities")
  data class Activities(
    val activities: List<ProtocolActivity>
  ) : BlockContent

  @Serializable
  @SerialName("Feed")
  data class Feed(
    val feedId: String, val items: List<FeedItem>, val ordering: FeedOrdering = FeedOrdering.CHRONOLOGICAL
  ) : BlockContent {
    init {
      require(feedId.isNotBlank()) { "Feed ID must not be blank." }
    }
  }
}

@Serializable
data class PresentationBlock(
  val id: BlockId,
  val content: BlockContent,
  val title: String? = null,
  val region: PresentationRegion = PresentationRegion.MAIN
)

@Serializable
data class PresentationDocument(
  val revision: Revision = Revision.ZERO, val blocks: List<PresentationBlock> = emptyList()
) {
  init {
    val ids = blocks.map { it.id }
    require(ids.distinct().size == ids.size) { "PresentationDocument cannot contain duplicate block IDs." }
  }
}

@Serializable
@SerialName("PresentationSnapshot")
data class PresentationSnapshot(
  override val metadata: MessageMetadata, val document: PresentationDocument
) : ServerMessage

@Serializable
sealed interface PatchOperation {
  @Serializable
  @SerialName("Upsert")
  data class Upsert(val block: PresentationBlock) : PatchOperation

  @Serializable
  @SerialName("Remove")
  data class Remove(val blockId: BlockId) : PatchOperation

  @Serializable
  @SerialName("Append")
  data class Append(val block: PresentationBlock) : PatchOperation

  @Serializable
  @SerialName("Clear")
  data object Clear : PatchOperation
}

@Serializable
@SerialName("PresentationPatch")
data class PresentationPatch(
  override val metadata: MessageMetadata,
  val baseRevision: Revision,
  val newRevision: Revision,
  val operations: List<PatchOperation> = emptyList()
) : ServerMessage {
  init {
    require(newRevision > baseRevision) { "newRevision must be greater than baseRevision." }
  }
}

@Serializable
@JvmInline
value class ActivityProtocolId(val value: String) {
  init {
    require(value.isNotBlank()) { "ActivityProtocolId must not be blank." }
  }
}

@Serializable
data class ProtocolActivity(
  val activityId: String,
  val description: String,
  val status: String,
  val mode: String,
  val progress: Double? = null,
  val remainingTimeSeconds: Long? = null,
  val elapsedTimeSeconds: Long,
  val cancellable: Boolean,
  val cancelChoiceId: String? = null,
  val cancelShortcut: String? = null
) {
  init {
    require(activityId.isNotBlank()) { "Activity ID must not be blank." }
    require(description.isNotBlank()) { "Description must not be blank." }
    require(status.isNotBlank()) { "Status must not be blank." }
    require(mode.isNotBlank()) { "Mode must not be blank." }
  }
}