@file:Suppress("unused")

package com.lucasalfare.flrpg.communicationprotocol

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ProtocolJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  prettyPrint = false
}

fun ProtocolMessage.toJson(): String = ProtocolJson.encodeToString(this)
inline fun <reified T : ProtocolMessage> String.toProtocolMessage(): T = ProtocolJson.decodeFromString(this)

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

// this is a Mc-Gyverism (?) 💀
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
  override val metadata: MessageMetadata, val result: Map<String, String> = emptyMap()
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
  val id: String, val text: String, val enabled: Boolean = true
) {
  init {
    require(id.isNotBlank()) { "Choice ID must not be blank." }
  }
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
  val id: BlockId, val content: BlockContent
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

fun PresentationDocument.applySnapshot(snapshot: PresentationSnapshot): PresentationDocument {
  return snapshot.document
}

fun PresentationDocument.applyPatch(patch: PresentationPatch): PresentationDocument {
  require(this.revision == patch.baseRevision) {
    "Cannot apply patch: current revision (${this.revision.value}) does not match patch base revision (${patch.baseRevision.value})."
  }

  val workingBlocks = this.blocks.toMutableList()

  for (op in patch.operations) {
    when (op) {
      is PatchOperation.Upsert -> {
        val index = workingBlocks.indexOfFirst { it.id == op.block.id }
        if (index != -1) {
          workingBlocks[index] = op.block
        } else {
          workingBlocks.add(op.block)
        }
      }

      is PatchOperation.Remove -> {
        val removed = workingBlocks.removeIf { it.id == op.blockId }
        require(removed) { "Cannot remove block '${op.blockId.value}': block does not exist." }
      }

      is PatchOperation.Append -> {
        require(workingBlocks.none { it.id == op.block.id }) {
          "Cannot append block '${op.block.id.value}': block ID already exists."
        }
        workingBlocks.add(op.block)
      }

      is PatchOperation.Clear -> {
        workingBlocks.clear()
      }
    }
  }

  return PresentationDocument(
    revision = patch.newRevision, blocks = workingBlocks
  )
}

@Serializable
enum class SequenceStatus {
  EXPECTED, DUPLICATE, GAP, STALE, OUT_OF_ORDER;

  val isAccepted: Boolean get() = this == EXPECTED
  val isDuplicate: Boolean get() = this == DUPLICATE
  val isOutOfOrder: Boolean get() = this == GAP || this == STALE || this == OUT_OF_ORDER
}

class SequenceTracker(
  initialExpectedSequence: Sequence = Sequence.ZERO
) {
  var expectedSequence: Sequence = initialExpectedSequence
    private set

  private val processedMessageIds = ConcurrentHashMap.newKeySet<MessageId>()

  @Synchronized
  fun process(message: ProtocolMessage): SequenceStatus = process(message.messageId, message.sequence)

  @Synchronized
  fun process(messageId: MessageId, sequence: Sequence): SequenceStatus {
    if (processedMessageIds.contains(messageId)) {
      return SequenceStatus.DUPLICATE
    }

    val distance = expectedSequence.distanceTo(sequence)
    return when {
      distance == 0L -> {
        processedMessageIds.add(messageId)
        expectedSequence = sequence.next()
        SequenceStatus.EXPECTED
      }

      distance > 0L && distance <= Sequence.HALF_MAX_VALUE -> {
        SequenceStatus.GAP
      }

      else -> {
        SequenceStatus.STALE
      }
    }
  }

  fun isProcessed(messageId: MessageId): Boolean = processedMessageIds.contains(messageId)

  @Synchronized
  fun reset(newExpectedSequence: Sequence = Sequence.ZERO) {
    expectedSequence = newExpectedSequence
    processedMessageIds.clear()
  }
}

class SessionSequenceTracker(
  val sessionId: SessionId,
  initialClientSequence: Sequence = Sequence.ZERO,
  initialServerSequence: Sequence = Sequence.ZERO
) {
  val clientToServer = SequenceTracker(initialClientSequence)
  val serverToClient = SequenceTracker(initialServerSequence)

  fun processInbound(message: ClientMessage): SequenceStatus = clientToServer.process(message)

  fun processInbound(message: ServerMessage): SequenceStatus = serverToClient.process(message)

  fun reset(
    clientSequence: Sequence = Sequence.ZERO, serverSequence: Sequence = Sequence.ZERO
  ) {
    clientToServer.reset(clientSequence)
    serverToClient.reset(serverSequence)
  }
}

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

data class ProtocolLimits(
  val maxTextLength: Int = 4096,
  val maxPatchOperations: Int = 100,
  val maxBlocksPerDocument: Int = 50,
  val maxChoicesPerBlock: Int = 20,
  val maxItemsPerList: Int = 100,
  val maxParametersPerAction: Int = 50
)

sealed interface ValidationError {
  val message: String

  data class Structural(override val message: String) : ValidationError
  data class Protocol(override val message: String) : ValidationError
}

sealed interface ValidationResult {
  data object Valid : ValidationResult
  data class Invalid(val error: ValidationError) : ValidationResult

  val isValid: Boolean get() = this is Valid
}

class StructuralValidator(val limits: ProtocolLimits = ProtocolLimits()) {

  fun validate(message: ProtocolMessage): ValidationResult {
    if (message.messageId.value.isBlank()) return invalid("MessageId cannot be blank.")
    if (message.sessionId.value.isBlank()) return invalid("SessionId cannot be blank.")
    message.correlationId?.let {
      if (it.value.isBlank()) return invalid("CorrelationId cannot be blank.")
    }

    return when (message) {
      is ClientHello -> ValidationResult.Valid
      is ServerWelcome -> ValidationResult.Valid
      is ServerReject -> ValidationResult.Valid
      is ClientAction -> validateAction(message)
      is ClientSelection -> validateSelection(message)
      is ClientTextCommand -> validateTextCommand(message)
      is ClientCancel -> ValidationResult.Valid
      is ClientResyncRequest -> ValidationResult.Valid
      is ServerAccepted -> validateAccepted(message)
      is ServerRejected -> validateRejected(message)
      is ServerCompleted -> validateCompleted(message)
      is ServerFailed -> validateFailed(message)
      is ServerProtocolError -> validateProtocolError(message)
      is ServerEvent -> validateEvent(message)
      is PresentationSnapshot -> validateDocument(message.document)
      is PresentationPatch -> validatePatch(message)
    }
  }

  fun validateDocument(document: PresentationDocument): ValidationResult {
    if (document.blocks.size > limits.maxBlocksPerDocument) {
      return invalid("Document exceeds maximum block count of ${limits.maxBlocksPerDocument}.")
    }
    val blockIds = mutableSetOf<String>()
    for (block in document.blocks) {
      if (block.id.value.isBlank()) return invalid("BlockId cannot be blank.")
      if (!blockIds.add(block.id.value)) return invalid("Duplicate block ID '${block.id.value}'.")
      val contentResult = validateBlockContent(block.content)
      if (!contentResult.isValid) return contentResult
    }
    return ValidationResult.Valid
  }

  fun validatePatch(patch: PresentationPatch): ValidationResult {
    if (patch.newRevision <= patch.baseRevision) {
      return invalid("Patch newRevision must be greater than baseRevision.")
    }
    if (patch.operations.size > limits.maxPatchOperations) {
      return invalid("Patch exceeds maximum operations count of ${limits.maxPatchOperations}.")
    }
    for (op in patch.operations) {
      when (op) {
        is PatchOperation.Upsert -> {
          val res = validateBlockContent(op.block.content)
          if (!res.isValid) return res
        }

        is PatchOperation.Append -> {
          val res = validateBlockContent(op.block.content)
          if (!res.isValid) return res
        }

        is PatchOperation.Remove -> {
          if (op.blockId.value.isBlank()) return invalid("BlockId cannot be blank.")
        }

        is PatchOperation.Clear -> {}
      }
    }
    return ValidationResult.Valid
  }

  private fun validateBlockContent(content: BlockContent): ValidationResult {
    return when (content) {
      is BlockContent.Text -> {
        if (content.text.length > limits.maxTextLength) {
          invalid("Text length exceeds maximum of ${limits.maxTextLength}.")
        } else ValidationResult.Valid
      }

      is BlockContent.ItemsList -> {
        if (content.items.size > limits.maxItemsPerList) {
          return invalid("Items list size exceeds maximum of ${limits.maxItemsPerList}.")
        }
        for (item in content.items) {
          if (item.length > limits.maxTextLength) {
            return invalid("List item length exceeds maximum of ${limits.maxTextLength}.")
          }
        }
        ValidationResult.Valid
      }

      is BlockContent.Choices -> {
        if (content.choices.size > limits.maxChoicesPerBlock) {
          return invalid("Choices count exceeds maximum of ${limits.maxChoicesPerBlock}.")
        }
        for (choice in content.choices) {
          if (choice.id.isBlank()) return invalid("Choice ID cannot be blank.")
          if (choice.text.length > limits.maxTextLength) {
            return invalid("Choice text length exceeds maximum of ${limits.maxTextLength}.")
          }
        }
        ValidationResult.Valid
      }

      is BlockContent.Feed -> {
        if (content.feedId.isBlank()) return invalid("Feed ID cannot be blank.")
        if (content.items.size > limits.maxItemsPerList) {
          return invalid("Feed items count exceeds maximum of ${limits.maxItemsPerList}.")
        }
        for (item in content.items) {
          if (item.id.isBlank()) return invalid("Feed item ID cannot be blank.")
          if (item.text.length > limits.maxTextLength) {
            return invalid("Feed item text length exceeds maximum of ${limits.maxTextLength}.")
          }
        }
        ValidationResult.Valid
      }
    }
  }

  private fun validateAction(action: ClientAction): ValidationResult {
    if (action.actionId.isBlank()) return invalid("Action ID cannot be blank.")
    if (action.parameters.size > limits.maxParametersPerAction) {
      return invalid("Parameters count exceeds maximum of ${limits.maxParametersPerAction}.")
    }
    for ((k, v) in action.parameters) {
      if (k.length > limits.maxTextLength || v.length > limits.maxTextLength) {
        return invalid("Action parameter length exceeds maximum of ${limits.maxTextLength}.")
      }
    }
    return ValidationResult.Valid
  }

  private fun validateSelection(selection: ClientSelection): ValidationResult {
    if (selection.choiceId.isBlank()) return invalid("Choice ID cannot be blank.")
    return ValidationResult.Valid
  }

  private fun validateTextCommand(command: ClientTextCommand): ValidationResult {
    if (command.rawText.isBlank()) return invalid("Raw text cannot be blank.")
    if (command.rawText.length > limits.maxTextLength) {
      return invalid("Text command length exceeds maximum of ${limits.maxTextLength}.")
    }
    return ValidationResult.Valid
  }

  private fun validateAccepted(accepted: ServerAccepted): ValidationResult {
    accepted.detail?.let {
      if (it.length > limits.maxTextLength) return invalid("Detail length exceeds maximum of ${limits.maxTextLength}.")
    }
    return ValidationResult.Valid
  }

  private fun validateRejected(rejected: ServerRejected): ValidationResult {
    if (rejected.reason.isBlank()) return invalid("Reason cannot be blank.")
    if (rejected.reason.length > limits.maxTextLength) return invalid("Reason length exceeds maximum of ${limits.maxTextLength}.")
    return ValidationResult.Valid
  }

  private fun validateCompleted(completed: ServerCompleted): ValidationResult {
    for ((k, v) in completed.result) {
      if (k.length > limits.maxTextLength || v.length > limits.maxTextLength) {
        return invalid("Result entry length exceeds maximum of ${limits.maxTextLength}.")
      }
    }
    return ValidationResult.Valid
  }

  private fun validateFailed(failed: ServerFailed): ValidationResult {
    if (failed.error.isBlank()) return invalid("Error message cannot be blank.")
    if (failed.error.length > limits.maxTextLength) return invalid("Error message length exceeds maximum of ${limits.maxTextLength}.")
    return ValidationResult.Valid
  }

  private fun validateProtocolError(error: ServerProtocolError): ValidationResult {
    if (error.code.isBlank()) return invalid("Code cannot be blank.")
    if (error.description.isBlank()) return invalid("Description cannot be blank.")
    if (error.description.length > limits.maxTextLength) return invalid("Description length exceeds maximum of ${limits.maxTextLength}.")
    return ValidationResult.Valid
  }

  private fun validateEvent(event: ServerEvent): ValidationResult {
    if (event.type.isBlank()) return invalid("Event type cannot be blank.")
    for ((k, v) in event.payload) {
      if (k.length > limits.maxTextLength || v.length > limits.maxTextLength) {
        return invalid("Payload entry length exceeds maximum of ${limits.maxTextLength}.")
      }
    }
    return ValidationResult.Valid
  }

  private fun invalid(message: String) = ValidationResult.Invalid(ValidationError.Structural(message))
}

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

@Serializable
enum class FailureAction {
  REJECT, IGNORE, DISCARD, RESPOND_WITH_ERROR, REQUEST_RESYNC, TERMINATE_SESSION
}

class FailureEvaluator(
  val limits: ProtocolLimits = ProtocolLimits()
) {
  fun evaluateValidationFailure(result: ValidationResult.Invalid): FailureAction {
    val err = result.error
    return when (err) {
      is ValidationError.Structural -> FailureAction.RESPOND_WITH_ERROR
      is ValidationError.Protocol -> {
        if (err.message.contains("Duplicate") || err.message.contains("Stale")) {
          FailureAction.DISCARD
        } else if (err.message.contains("Sequence gap") || err.message.contains("Revision mismatch") || err.message.contains(
            "Out-of-order"
          )
        ) {
          FailureAction.REQUEST_RESYNC
        } else {
          FailureAction.RESPOND_WITH_ERROR
        }
      }
    }
  }

  fun evaluateSequenceStatus(status: SequenceStatus): FailureAction {
    return when (status) {
      SequenceStatus.EXPECTED -> FailureAction.IGNORE
      SequenceStatus.DUPLICATE, SequenceStatus.STALE -> FailureAction.DISCARD
      SequenceStatus.GAP, SequenceStatus.OUT_OF_ORDER -> FailureAction.REQUEST_RESYNC
    }
  }

  fun evaluateSessionResult(sessionState: SessionState): FailureAction {
    return when (sessionState) {
      SessionState.REJECTED -> FailureAction.REJECT
      SessionState.NON_EXISTENT -> FailureAction.TERMINATE_SESSION
      SessionState.ESTABLISHED, SessionState.RESUMED -> FailureAction.IGNORE
    }
  }
}

class RequestRateLimiter(
  val maxRequestsPerWindow: Int = 100
) {
  private val requestCounts = ConcurrentHashMap<SessionId, Int>()

  fun allowRequest(sessionId: SessionId): Boolean {
    val current = requestCounts.compute(sessionId) { _, count -> (count ?: 0) + 1 } ?: 0
    return current <= maxRequestsPerWindow
  }

  fun reset(sessionId: SessionId) {
    requestCounts.remove(sessionId)
  }

  fun evaluateViolation(excessiveViolationsCount: Int): FailureAction {
    return if (excessiveViolationsCount >= 3) {
      FailureAction.TERMINATE_SESSION
    } else {
      FailureAction.RESPOND_WITH_ERROR
    }
  }
}