package com.lucasalfare.flrpg.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.communicationprotocol.Sequence.Companion.ZERO
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*
import com.lucasalfare.flrpg.communicationprotocol.TextStyle as ProtocolTextStyle

// connection

class ClientConnection(
  private val onServerMessage: (ServerMessage) -> Unit,
  private val onConnectionMessage: (String) -> Unit,
  private val onOperationStarted: (MessageId) -> Unit,
  private val serverUrl: String = "ws://localhost:8080/game",
) {
  private val client = HttpClient(CIO) {
    install(WebSockets)
  }

  private val sessionId = SessionId(UUID.randomUUID().toString())

  private var nextSequence: Sequence = ZERO
  private var handshakeDone = false

  private var job: Job? = null
  private var session: DefaultClientWebSocketSession? = null

  fun connect(scope: CoroutineScope) {
    if (job?.isActive == true) return

    job = scope.launch {
      handshakeDone = false

      try {
        onConnectionMessage("A LIGAR...")

        client.webSocket(serverUrl) {
          session = this
          performHandshake()

          for (frame in incoming) {
            if (frame is Frame.Text) {
              val message = frame.readText().toProtocolMessage<ProtocolMessage>()

              if (message is ServerMessage) {
                receiveFromNetwork(message)
              }
            }
          }
        }
      } catch (e: Exception) {
        onConnectionMessage("FALHA NA LIGAÇÃO · ${e.message}")
      } finally {
        session = null
        handshakeDone = false
        onConnectionMessage("OFFLINE · sem ligação ao servidor")
      }
    }
  }

  private suspend fun DefaultClientWebSocketSession.performHandshake() {
    val hello = ClientHello(metadata = nextMetadata())
    send(Frame.Text(hello.toJson()))
  }

  fun sendSelection(choiceId: String) {
    val currentSession = session

    if (currentSession == null || !handshakeDone) {
      onConnectionMessage("NÃO PRONTO · handshake pendente")
      return
    }

    val metadata = nextMetadata()
    val selection = ClientSelection(
      metadata = metadata,
      choiceId = choiceId,
    )

    onOperationStarted(metadata.messageId)
    onConnectionMessage(
      "A ENVIAR SELEÇÃO · $choiceId (seq ${selection.sequence.value})"
    )

    currentSession.launch {
      try {
        currentSession.send(Frame.Text(selection.toJson()))
      } catch (e: Exception) {
        onConnectionMessage("FALHA NO ENVIO · ${e.message}")
      }
    }
  }

  fun sendTextCommand(rawText: String) {
    val currentSession = session

    if (currentSession == null || !handshakeDone) {
      onConnectionMessage("NÃO PRONTO · handshake pendente")
      return
    }

    val metadata = nextMetadata()
    val textCommand = ClientTextCommand(
      metadata = metadata,
      rawText = rawText,
    )

    onConnectionMessage(
      "A ENVIAR COMANDO · $rawText (seq ${textCommand.sequence.value})"
    )

    currentSession.launch {
      try {
        currentSession.send(Frame.Text(textCommand.toJson()))
      } catch (e: Exception) {
        onConnectionMessage("FALHA NO ENVIO · ${e.message}")
      }
    }
  }

  fun sendCancel(targetMessageId: MessageId) {
    val currentSession = session

    if (currentSession == null || !handshakeDone) {
      onConnectionMessage("NÃO PRONTO · handshake pendente")
      return
    }

    val cancelMessage = ClientCancel(
      metadata = nextMetadata(),
      targetMessageId = targetMessageId,
    )

    onConnectionMessage(
      "A ENVIAR CANCELAMENTO · alvo ${targetMessageId.value}"
    )

    currentSession.launch {
      try {
        currentSession.send(Frame.Text(cancelMessage.toJson()))
      } catch (e: Exception) {
        onConnectionMessage("FALHA NO ENVIO · ${e.message}")
      }
    }
  }

  private fun receiveFromNetwork(message: ServerMessage) {
    when (message) {
      is ServerWelcome -> {
        nextSequence = ZERO
        handshakeDone = true
        onServerMessage(message)
      }

      else -> onServerMessage(message)
    }
  }

  private fun nextMetadata(
    correlationId: CorrelationId? = null,
  ): MessageMetadata {
    val metadata = MessageMetadata(
      messageId = MessageId(UUID.randomUUID().toString()),
      sessionId = sessionId,
      sequence = nextSequence,
      correlationId = correlationId,
    )

    nextSequence = nextSequence.next()
    return metadata
  }

  fun close() {
    job?.cancel()
    session = null
    handshakeDone = false
    client.close()
  }
}

// workspace model

@JvmInline
value class TileId(val value: Int)

enum class TileViewType {
  WORLD, HELP, WORKSPACE, HISTORY, ACTIVITY,
}

enum class TileLifecycle {
  ACTIVE, MINIMIZED,
}

data class TileInstance(
  val id: TileId,
  val viewType: TileViewType,
  val lifecycle: TileLifecycle,
  val title: String,
)

class WorkspaceState {
  private val instances = linkedMapOf<TileId, TileInstance>()
  private var nextIdValue = 1

  val mainTileId: TileId
  var selectedId: TileId
    private set
  var maximizedId: TileId? = null
    private set
  var logicalWidthDp: Float = 1280f
  var logicalHeightDp: Float = 720f
  var layoutRoot: WorkspaceLayoutNode
    private set

  init {
    val worldId = allocateId()
    val world = TileInstance(
      id = worldId,
      viewType = TileViewType.WORLD,
      lifecycle = TileLifecycle.ACTIVE,
      title = "World",
    )
    instances[worldId] = world
    mainTileId = worldId
    selectedId = worldId
    layoutRoot = WorkspaceLayoutNode.Leaf(worldId)
  }

  fun instance(id: TileId): TileInstance? = instances[id]

  fun allInstances(): Collection<TileInstance> = instances.values

  fun activeInstances(): List<TileInstance> = instances.values.filter { it.lifecycle == TileLifecycle.ACTIVE }

  fun minimizedInstances(): List<TileInstance> = instances.values.filter { it.lifecycle == TileLifecycle.MINIMIZED }

  fun isCompactMode(): Boolean {
    val geometries = computeLeafGeometries(layoutRoot, 0f, 0f, logicalWidthDp, logicalHeightDp)
    return geometries.any { it.width < MIN_TILE_WIDTH_DP || it.height < MIN_TILE_HEIGHT_DP }
  }

  fun open(viewTextualId: String): Boolean {
    val definition = TileViewRegistry.definition(viewTextualId) ?: return false

    if (definition.singleInstance) {
      val existing = instances.values.firstOrNull { it.viewType == definition.type }
      if (existing != null) {
        return when (existing.lifecycle) {
          TileLifecycle.ACTIVE -> {
            clearMaximization()
            selectedId = existing.id
            true
          }

          TileLifecycle.MINIMIZED -> restoreMinimized(existing.id)
        }
      }
    }

    val newId = allocateId()
    val newInstance = TileInstance(
      id = newId,
      viewType = definition.type,
      lifecycle = TileLifecycle.ACTIVE,
      title = definition.defaultTitle,
    )
    instances[newId] = newInstance

    clearMaximization()
    insertIntoLargestRegion(newId)
    selectedId = newId
    return true
  }

  fun minimize(id: TileId): Boolean {
    if (id == mainTileId) return false

    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(id)) return false

    val newRoot = layoutRoot.removeLeaf(id) ?: return false

    clearMaximization()
    layoutRoot = newRoot
    instances[id] = current.copy(lifecycle = TileLifecycle.MINIMIZED)

    if (selectedId == id) {
      selectedId = newRoot.visualLeaves().first()
    }

    return true
  }

  fun restore(id: TileId): Boolean = restoreMinimized(id)

  private fun restoreMinimized(id: TileId): Boolean {
    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.MINIMIZED) return false

    instances[id] = current.copy(lifecycle = TileLifecycle.ACTIVE)
    clearMaximization()
    insertIntoLargestRegion(id)
    selectedId = id
    return true
  }

  fun close(id: TileId): Boolean {
    if (id == mainTileId) return false

    val current = instances[id] ?: return false

    return when (current.lifecycle) {
      TileLifecycle.ACTIVE -> {
        if (!layoutRoot.contains(id)) return false

        val newRoot = layoutRoot.removeLeaf(id) ?: return false

        clearMaximization()
        layoutRoot = newRoot
        instances.remove(id)

        if (selectedId == id) {
          selectedId = newRoot.visualLeaves().first()
        }

        true
      }

      TileLifecycle.MINIMIZED -> {
        instances.remove(id)
        true
      }
    }
  }

  fun select(id: TileId): Boolean {
    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(id)) return false

    if (selectedId != id) {
      clearMaximization()
    }
    selectedId = id
    return true
  }

  fun selectNextInVisualOrder(): Boolean {
    val leaves = layoutRoot.visualLeaves()
    if (leaves.size <= 1) return false

    val index = leaves.indexOf(selectedId)
    if (index < 0) return false

    return select(leaves[(index + 1) % leaves.size])
  }

  fun selectPreviousInVisualOrder(): Boolean {
    val leaves = layoutRoot.visualLeaves()
    if (leaves.size <= 1) return false

    val index = leaves.indexOf(selectedId)
    if (index < 0) return false

    return select(leaves[(index - 1 + leaves.size) % leaves.size])
  }

  fun maximize(id: TileId): Boolean {
    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(id)) return false

    maximizedId = id
    selectedId = id
    return true
  }

  fun unmaximize(): Boolean {
    if (maximizedId == null) return false
    maximizedId = null
    return true
  }

  fun swap(firstId: TileId, secondId: TileId): Boolean {
    if (firstId == secondId) return false

    val first = instances[firstId] ?: return false
    val second = instances[secondId] ?: return false

    if (first.lifecycle != TileLifecycle.ACTIVE) return false
    if (second.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(firstId)) return false
    if (!layoutRoot.contains(secondId)) return false

    clearMaximization()
    layoutRoot = layoutRoot.swapLeaves(firstId, secondId)
    return true
  }

  private fun insertIntoLargestRegion(newId: TileId) {
    val geometries = computeLeafGeometries(layoutRoot, 0f, 0f, logicalWidthDp, logicalHeightDp)
    if (geometries.isEmpty()) {
      layoutRoot = WorkspaceLayoutNode.Leaf(newId)
      return
    }

    val target = selectRegionToSplit(geometries)
    val parentOrientation = layoutRoot.findParentOrientation(target.tileId)
    val orientation = if (parentOrientation != null) {
      if (parentOrientation == SplitOrientation.ROW) SplitOrientation.COLUMN else SplitOrientation.ROW
    } else {
      if (target.width >= target.height) SplitOrientation.ROW else SplitOrientation.COLUMN
    }

    val replacement = WorkspaceLayoutNode.Branch(
      orientation = orientation,
      first = WorkspaceLayoutNode.Leaf(target.tileId),
      second = WorkspaceLayoutNode.Leaf(newId),
    )

    layoutRoot = layoutRoot.replaceLeaf(target.tileId, replacement)
  }

  private data class LeafGeometry(
    val tileId: TileId,
    val width: Float,
    val height: Float,
  ) {
    val area: Float get() = width * height
  }

  private fun computeLeafGeometries(
    node: WorkspaceLayoutNode,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
  ): List<LeafGeometry> = when (node) {
    is WorkspaceLayoutNode.Leaf -> listOf(LeafGeometry(node.tileId, width, height))
    is WorkspaceLayoutNode.Branch -> {
      when (node.orientation) {
        SplitOrientation.ROW -> {
          val half = width / 2f
          computeLeafGeometries(node.first, x, y, half, height) + computeLeafGeometries(
            node.second, x + half, y, half, height
          )
        }

        SplitOrientation.COLUMN -> {
          val half = height / 2f
          computeLeafGeometries(node.first, x, y, width, half) + computeLeafGeometries(
            node.second, x, y + half, width, half
          )
        }
      }
    }
  }

  private fun selectRegionToSplit(geometries: List<LeafGeometry>): LeafGeometry {
    val maxArea = geometries.maxOf { it.area }
    val candidates = geometries.filter { it.area == maxArea }

    if (candidates.size == 1) return candidates.first()

    val containingSelected = candidates.firstOrNull { it.tileId == selectedId }
    if (containingSelected != null) return containingSelected

    val visualOrder = layoutRoot.visualLeaves()
    return candidates.minBy { visualOrder.indexOf(it.tileId) }
  }

  private fun clearMaximization() {
    maximizedId = null
  }

  private fun allocateId(): TileId {
    val id = TileId(nextIdValue)
    nextIdValue += 1
    return id
  }

  companion object {
    const val MIN_TILE_WIDTH_DP = 280f
    const val MIN_TILE_HEIGHT_DP = 180f
  }
}

private fun WorkspaceLayoutNode.replaceLeaf(
  targetId: TileId,
  replacement: WorkspaceLayoutNode,
): WorkspaceLayoutNode = when (this) {
  is WorkspaceLayoutNode.Leaf -> if (tileId == targetId) replacement else this
  is WorkspaceLayoutNode.Branch -> {
    val newFirst = first.replaceLeaf(targetId, replacement)
    val newSecond = second.replaceLeaf(targetId, replacement)
    if (newFirst === first && newSecond === second) this
    else copy(first = newFirst, second = newSecond)
  }
}

private fun WorkspaceLayoutNode.removeLeaf(targetId: TileId): WorkspaceLayoutNode? = when (this) {
  is WorkspaceLayoutNode.Leaf -> if (tileId == targetId) null else this
  is WorkspaceLayoutNode.Branch -> {
    val newFirst = first.removeLeaf(targetId)
    val newSecond = second.removeLeaf(targetId)
    when {
      newFirst == null -> newSecond
      newSecond == null -> newFirst
      newFirst === first && newSecond === second -> this
      else -> copy(first = newFirst, second = newSecond)
    }
  }
}

enum class SplitOrientation {
  ROW, COLUMN,
}

sealed class WorkspaceLayoutNode {
  data class Leaf(val tileId: TileId) : WorkspaceLayoutNode()

  data class Branch(
    val orientation: SplitOrientation,
    val first: WorkspaceLayoutNode,
    val second: WorkspaceLayoutNode,
  ) : WorkspaceLayoutNode()

  fun leafCount(): Int = when (this) {
    is Leaf -> 1
    is Branch -> first.leafCount() + second.leafCount()
  }

  fun visualLeaves(): List<TileId> = when (this) {
    is Leaf -> listOf(tileId)
    is Branch -> first.visualLeaves() + second.visualLeaves()
  }

  fun findLeaf(id: TileId): Leaf? = when (this) {
    is Leaf -> if (tileId == id) this else null
    is Branch -> first.findLeaf(id) ?: second.findLeaf(id)
  }

  fun contains(id: TileId): Boolean = findLeaf(id) != null

  fun hasDuplicateIds(): Boolean {
    val seen = mutableSetOf<TileId>()
    for (id in visualLeaves()) {
      if (!seen.add(id)) return true
    }
    return false
  }

  fun swapLeaves(a: TileId, b: TileId): WorkspaceLayoutNode = when (this) {
    is Leaf -> when (tileId) {
      a -> Leaf(b)
      b -> Leaf(a)
      else -> this
    }

    is Branch -> {
      val newFirst = first.swapLeaves(a, b)
      val newSecond = second.swapLeaves(a, b)
      if (newFirst === first && newSecond === second) this
      else copy(first = newFirst, second = newSecond)
    }
  }

  fun findParentOrientation(id: TileId, parentOrientation: SplitOrientation? = null): SplitOrientation? = when (this) {
    is Leaf -> if (tileId == id) parentOrientation else null
    is Branch -> first.findParentOrientation(id, orientation) ?: second.findParentOrientation(id, orientation)
  }
}

data class ViewDefinition(
  val type: TileViewType,
  val textualId: String,
  val defaultTitle: String,
  val singleInstance: Boolean,
)

object TileViewRegistry {
  private val byTextualId = mapOf(
    "world" to ViewDefinition(
      type = TileViewType.WORLD,
      textualId = "world",
      defaultTitle = "World",
      singleInstance = true,
    ),
    "help" to ViewDefinition(
      type = TileViewType.HELP,
      textualId = "help",
      defaultTitle = "Help",
      singleInstance = true,
    ),
    "workspace" to ViewDefinition(
      type = TileViewType.WORKSPACE,
      textualId = "workspace",
      defaultTitle = "Workspace",
      singleInstance = true,
    ),
    "history" to ViewDefinition(
      type = TileViewType.HISTORY,
      textualId = "history",
      defaultTitle = "History",
      singleInstance = true,
    ),
    "activity" to ViewDefinition(
      type = TileViewType.ACTIVITY,
      textualId = "activity",
      defaultTitle = "Activity",
      singleInstance = true,
    ),
  )

  private val byType = byTextualId.values.associateBy { it.type }

  fun isRegistered(textualId: String): Boolean = byTextualId.containsKey(textualId.lowercase())

  fun isRegistered(type: TileViewType): Boolean = byType.containsKey(type)

  fun definition(textualId: String): ViewDefinition? = byTextualId[textualId.lowercase()]

  fun definition(type: TileViewType): ViewDefinition? = byType[type]

  fun defaultTitle(type: TileViewType): String? = byType[type]?.defaultTitle

  fun allDefinitions(): Collection<ViewDefinition> = byTextualId.values
}

// local UI command parser

sealed class LocalUiCommand {
  data object Help : LocalUiCommand()
  data object Tiles : LocalUiCommand()
  data class Open(val viewTextualId: String) : LocalUiCommand()
  data class Focus(val id: TileId) : LocalUiCommand()
  data class Close(val id: TileId?) : LocalUiCommand()
  data class Minimize(val id: TileId?) : LocalUiCommand()
  data class Restore(val id: TileId) : LocalUiCommand()
  data class Maximize(val id: TileId?) : LocalUiCommand()
  data object Unmaximize : LocalUiCommand()
  data class Swap(val firstId: TileId, val secondId: TileId) : LocalUiCommand()
}

sealed class UiCommandParseResult {
  data class Success(val command: LocalUiCommand) : UiCommandParseResult()
  data class Failure(val reason: String) : UiCommandParseResult()
  data object NotUiCommand : UiCommandParseResult()
}

fun parseUiCommand(input: String): UiCommandParseResult {
  val trimmed = input.trim()
  if (trimmed.isEmpty()) return UiCommandParseResult.NotUiCommand

  val tokens = trimmed.split(Regex("\\s+"))
  if (tokens.isEmpty()) return UiCommandParseResult.NotUiCommand

  if (!tokens[0].equals("ui", ignoreCase = true)) {
    return UiCommandParseResult.NotUiCommand
  }

  if (tokens.size == 1) {
    return UiCommandParseResult.Failure("missing command after 'ui'")
  }

  val verb = tokens[1].lowercase()
  val args = tokens.drop(2)

  fun parseTileId(raw: String): TileId? = raw.toIntOrNull()?.let { TileId(it) }

  return when (verb) {
    "help" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui help' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Help)
      }
    }

    "tiles" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui tiles' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Tiles)
      }
    }

    "open" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Failure("'ui open' requires a view identifier")
        args.size > 1 -> UiCommandParseResult.Failure("'ui open' accepts only one view identifier")
        else -> {
          val viewId = args[0].lowercase()
          if (!TileViewRegistry.isRegistered(viewId)) {
            UiCommandParseResult.Failure("unknown view '$viewId'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Open(viewId))
          }
        }
      }
    }

    "focus" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Failure("'ui focus' requires a tile id")
        args.size > 1 -> UiCommandParseResult.Failure("'ui focus' accepts only one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Focus(id))
          }
        }
      }
    }

    "close" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Success(LocalUiCommand.Close(null))
        args.size > 1 -> UiCommandParseResult.Failure("'ui close' accepts at most one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Close(id))
          }
        }
      }
    }

    "minimize" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Success(LocalUiCommand.Minimize(null))
        args.size > 1 -> UiCommandParseResult.Failure("'ui minimize' accepts at most one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Minimize(id))
          }
        }
      }
    }

    "restore" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Failure("'ui restore' requires a tile id")
        args.size > 1 -> UiCommandParseResult.Failure("'ui restore' accepts only one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Restore(id))
          }
        }
      }
    }

    "maximize" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Success(LocalUiCommand.Maximize(null))
        args.size > 1 -> UiCommandParseResult.Failure("'ui maximize' accepts at most one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Maximize(id))
          }
        }
      }
    }

    "unmaximize" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui unmaximize' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Unmaximize)
      }
    }

    "swap" -> {
      when {
        args.size < 2 -> UiCommandParseResult.Failure("'ui swap' requires two tile ids")
        args.size > 2 -> UiCommandParseResult.Failure("'ui swap' accepts exactly two tile ids")
        else -> {
          val first = parseTileId(args[0])
          val second = parseTileId(args[1])
          when {
            first == null -> UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
            second == null -> UiCommandParseResult.Failure("invalid tile id '${args[1]}'")
            first == second -> UiCommandParseResult.Failure("cannot swap a tile with itself")
            else -> UiCommandParseResult.Success(LocalUiCommand.Swap(first, second))
          }
        }
      }
    }

    else -> UiCommandParseResult.Failure("unknown ui command '$verb'")
  }
}

//state

private enum class ClientScreen {
  PRESENTATION, MENU,
}

class ClientPresentationState {

  private var serverDocument by mutableStateOf(PresentationDocument())

  private val recentHistory = mutableStateListOf<FeedItem>()

  private val clientHistoryBlockId = BlockId("client_recent_history")

  var activeOperationMessageId: MessageId? by mutableStateOf(null)

  val document: PresentationDocument
    get() {
      val base = serverDocument
      val otherBlocks = base.blocks.filter {
        it.region != PresentationRegion.HISTORY
      }

      val historyBlock = PresentationBlock(
        id = clientHistoryBlockId,
        content = BlockContent.Feed(
          feedId = "client_recent_events",
          items = recentHistory.toList(),
          ordering = FeedOrdering.CHRONOLOGICAL,
        ),
        title = null,
        region = PresentationRegion.HISTORY,
      )

      return base.copy(blocks = otherBlocks + historyBlock)
    }

  var darkThemeEnabled by mutableStateOf(true)
    private set

  private var screen by mutableStateOf(ClientScreen.PRESENTATION)

  val isMenuOpen: Boolean
    get() = screen == ClientScreen.MENU

  var input by mutableStateOf("")
    private set

  var connectionMessage by mutableStateOf(
    "OFFLINE · sem ligação ao servidor"
  )

  val workspace = WorkspaceState()

  var workspaceEpoch by mutableStateOf(0)
    private set

  private val commandHistory = mutableStateListOf<String>()

  private var historyIndex by mutableStateOf<Int?>(null)
  private var inputBeforeHistory = ""

  private fun touchWorkspace() {
    workspaceEpoch += 1
  }

  fun receive(message: ServerMessage) {
    when (message) {
      is ServerWelcome -> {
        connectionMessage = if (message.isResumed) {
          "CONECTADO · sessão retoma"
        } else {
          "CONECTADO · sessão estabelecida"
        }
      }

      is ServerReject -> {
        connectionMessage = "REJEITADO · ${message.reason}"
        activeOperationMessageId = null
      }

      is ServerAccepted -> {
        connectionMessage = "ACEITE · ${message.detail}"
      }

      is ServerCompleted -> {
        val factsSummary = message.facts.joinToString { it.name }

        connectionMessage = "CONCLUÍDO · factos: $factsSummary"

        message.facts.forEach { fact ->
          appendHistory(fact.name, fact.arguments)

          if (fact.name == "character_movement_completed" || fact.name == "character_movement_interrupted" || fact.name == "activity_cancelled") {
            activeOperationMessageId = null
          }
        }
      }

      is ServerEvent -> {
        connectionMessage = "EVENTO · ${message.type}"
        appendHistory(message.type, message.payload)
      }

      is PresentationSnapshot -> {
        serverDocument = message.document

        connectionMessage = "CONECTADO · revisão de apresentação ${serverDocument.revision.value}"
      }

      is PresentationPatch -> {
        val updatedDocument = runCatching {
          serverDocument.applyPatch(message)
        }

        updatedDocument.onSuccess {
          serverDocument = it

          connectionMessage = "CONECTADO · revisão de apresentação ${serverDocument.revision.value}"
        }.onFailure {
          connectionMessage = "APRESENTAÇÃO DESACTUALIZADA · snapshot necessário"
        }
      }

      is ServerRejected -> {
        connectionMessage = "SERVIDOR REJEITOU · ${message.reason}"
        activeOperationMessageId = null
      }

      is ServerFailed -> {
        connectionMessage = "FALHA NO SERVIDOR · ${message.error}"
        activeOperationMessageId = null
      }

      is ServerProtocolError -> {
        connectionMessage = "ERRO DE PROTOCOLO · ${message.description}"
      }
    }
  }

  private fun appendHistory(
    factName: String,
    args: Map<String, String>,
  ) {
    val text = describeFact(factName, args)

    if (text.isBlank()) return

    val entryId = "hist_${factName}_${recentHistory.size}_${System.nanoTime()}"

    if (recentHistory.size >= 20) {
      recentHistory.removeAt(0)
    }

    recentHistory.add(
      FeedItem(
        id = entryId,
        text = text,
        style = ProtocolTextStyle.MUTED,
      )
    )
  }

  private fun describeFact(
    name: String,
    args: Map<String, String>,
  ): String {
    val location = args["locationName"] ?: args["locationId"] ?: "an unknown location"

    val origin = args["originLocationName"] ?: args["originLocationId"] ?: "an unknown origin"

    val from = args["fromLocationName"] ?: args["fromLocationId"] ?: "an unknown origin"

    val to = args["toLocationName"] ?: args["toLocationId"] ?: "an unknown destination"

    return when (name) {
      "character_entered" -> "You entered the world at $location."

      "character_moved" -> "You began travelling from $from to $to."

      "character_movement_completed" -> "You arrived at $location (from $origin)."

      "character_movement_interrupted" -> "Your movement was interrupted; you are at $location."

      else -> name
    }
  }

  fun updateInput(value: String) {
    input = value
    historyIndex = null
    inputBeforeHistory = ""
  }

  fun submitCommand(
    selectionSink: (String) -> Unit,
    commandSink: (String) -> Unit,
    cancelSink: (MessageId) -> Unit,
  ) {
    val command = input.trim()

    if (command.isEmpty()) return

    when (val parseResult = parseUiCommand(command)) {
      is UiCommandParseResult.Success -> {
        connectionMessage = dispatchLocalUiCommand(parseResult.command)
        input = ""
        return
      }

      is UiCommandParseResult.Failure -> {
        connectionMessage = "UI · ${parseResult.reason}"
        input = ""
        return
      }

      UiCommandParseResult.NotUiCommand -> Unit
    }

    val activeChoice = findActiveChoice(command)

    if (activeChoice != null) {
      rememberCommand(command)
      selectionSink(activeChoice.id)
      input = ""
      return
    }

    if (handleClientCommand(command, cancelSink)) {
      input = ""
      return
    }

    rememberCommand(command)
    commandSink(command)
    input = ""
  }

  fun previousCommand(): Boolean {
    if (commandHistory.isEmpty()) return false

    val nextIndex = historyIndex?.let {
      (it - 1).coerceAtLeast(0)
    } ?: run {
      inputBeforeHistory = input
      commandHistory.lastIndex
    }

    historyIndex = nextIndex
    input = commandHistory[nextIndex]

    return true
  }

  fun nextCommand(): Boolean {
    val currentIndex = historyIndex ?: return false

    if (currentIndex < commandHistory.lastIndex) {
      historyIndex = currentIndex + 1
      input = commandHistory[currentIndex + 1]
    } else {
      historyIndex = null
      input = inputBeforeHistory
      inputBeforeHistory = ""
    }

    return true
  }

  fun selectNextTile(): Boolean {
    val changed = workspace.selectNextInVisualOrder()
    if (changed) touchWorkspace()
    return changed
  }

  fun selectPreviousTile(): Boolean {
    val changed = workspace.selectPreviousInVisualOrder()
    if (changed) touchWorkspace()
    return changed
  }

  fun selectTileByNumber(number: Int): Boolean {
    if (number !in 1..9) return false
    val changed = workspace.select(TileId(number))
    if (changed) touchWorkspace()
    return changed
  }

  private fun findActiveChoice(input: String): Choice? {
    val activeChoices =
      document.blocks.map { it.content }.filterIsInstance<BlockContent.Choices>().flatMap { it.choices }
        .filter { it.enabled }

    return activeChoices.firstOrNull {
      it.shortcut.equals(input, ignoreCase = true)
    } ?: activeChoices.firstOrNull {
      it.text.equals(input, ignoreCase = true)
    }
  }

  private fun handleClientCommand(
    command: String,
    cancelSink: (MessageId) -> Unit,
  ): Boolean {
    return when (command.uppercase()) {
      "M", "MENU" -> {
        screen = ClientScreen.MENU
        true
      }

      "V", "VOLTAR" -> {
        screen = ClientScreen.PRESENTATION
        true
      }

      "T", "TEMA" -> {
        if (screen == ClientScreen.MENU) {
          darkThemeEnabled = !darkThemeEnabled
          true
        } else {
          false
        }
      }

      "CANCEL", "CANCELAR" -> {
        val targetId = activeOperationMessageId

        if (targetId != null) {
          cancelSink(targetId)
        } else {
          connectionMessage = "NENHUMA ATIVIDADE ATIVA PARA CANCELAR"
        }

        true
      }

      else -> false
    }
  }

  private fun dispatchLocalUiCommand(command: LocalUiCommand): String {
    val message = when (command) {
      LocalUiCommand.Help -> {
        if (workspace.open("help")) {
          "UI · help opened"
        } else {
          "UI · failed to open help"
        }
      }

      LocalUiCommand.Tiles -> {
        if (workspace.open("workspace")) {
          "UI · workspace opened"
        } else {
          "UI · failed to open workspace"
        }
      }

      is LocalUiCommand.Open -> {
        if (workspace.open(command.viewTextualId)) {
          "UI · ${command.viewTextualId} opened"
        } else {
          "UI · failed to open ${command.viewTextualId}"
        }
      }

      is LocalUiCommand.Focus -> {
        if (workspace.select(command.id)) {
          "UI · focused tile ${command.id.value}"
        } else {
          "UI · cannot focus tile ${command.id.value}"
        }
      }

      is LocalUiCommand.Close -> {
        val target = command.id ?: workspace.selectedId
        if (workspace.close(target)) {
          "UI · closed tile ${target.value}"
        } else {
          "UI · cannot close tile ${target.value}"
        }
      }

      is LocalUiCommand.Minimize -> {
        val target = command.id ?: workspace.selectedId
        if (workspace.minimize(target)) {
          "UI · minimized tile ${target.value}"
        } else {
          "UI · cannot minimize tile ${target.value}"
        }
      }

      is LocalUiCommand.Restore -> {
        if (workspace.restore(command.id)) {
          "UI · restored tile ${command.id.value}"
        } else {
          "UI · cannot restore tile ${command.id.value}"
        }
      }

      is LocalUiCommand.Maximize -> {
        val target = command.id ?: workspace.selectedId
        if (workspace.maximize(target)) {
          "UI · maximized tile ${target.value}"
        } else {
          "UI · cannot maximize tile ${target.value}"
        }
      }

      LocalUiCommand.Unmaximize -> {
        if (workspace.unmaximize()) {
          "UI · unmaximized"
        } else {
          "UI · nothing to unmaximize"
        }
      }

      is LocalUiCommand.Swap -> {
        if (workspace.swap(command.firstId, command.secondId)) {
          "UI · swapped tiles ${command.firstId.value} and ${command.secondId.value}"
        } else {
          "UI · cannot swap tiles ${command.firstId.value} and ${command.secondId.value}"
        }
      }
    }
    touchWorkspace()
    return message
  }

  private fun rememberCommand(command: String) {
    if (commandHistory.size == 20) {
      commandHistory.removeAt(0)
    }

    commandHistory.add(command)
    historyIndex = null
    inputBeforeHistory = ""
  }
}

// UI

private object ClientUi {

  private data class TerminalTheme(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
  )

  private val darkTheme = TerminalTheme(
    background = Color(0xFF111318),
    foreground = Color(0xFFD7DCE5),
    muted = Color(0xFF778195),
    accent = Color(0xFF80C8A0),
    border = Color(0xFF394252),
  )

  private val lightTheme = TerminalTheme(
    background = Color(0xFFF4F3EE),
    foreground = Color(0xFF282B32),
    muted = Color(0xFF74777D),
    accent = Color(0xFF356C53),
    border = Color(0xFFB9B8B0),
  )

  private val mono = FontFamily.Monospace

  @Composable
  fun TerminalApp(
    state: ClientPresentationState,
    onSelection: (String) -> Unit,
    onCommand: (String) -> Unit,
    onCancel: (MessageId) -> Unit,
  ) {
    val theme = if (state.darkThemeEnabled) darkTheme else lightTheme

    Column(
      Modifier.fillMaxSize().background(theme.background).padding(horizontal = 18.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth()) {
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              "FL-RPG",
              color = theme.accent,
              fontSize = 18.sp,
              fontFamily = mono,
              fontWeight = FontWeight.Bold,
            )

            Text(
              "  ·  cliente textual",
              color = theme.muted,
              fontSize = 13.sp,
              fontFamily = mono,
            )

            Spacer(Modifier.weight(1f))

            Text(
              "[ ${if (state.darkThemeEnabled) "ESCURO" else "CLARO"} ]",
              color = theme.muted,
              fontSize = 12.sp,
              fontFamily = mono,
            )
          }

          Text(
            "─".repeat(100),
            color = theme.border,
            fontSize = 12.sp,
            fontFamily = mono,
            maxLines = 1,
          )
        }
      }

      if (state.isMenuOpen) {
        MenuScreen(
          theme = theme,
          modifier = Modifier.weight(1f).fillMaxWidth(),
        )
      } else {
        PresentationScreen(
          document = state.document,
          workspace = state.workspace,
          workspaceEpoch = state.workspaceEpoch,
          theme = theme,
          activeOperationMessageId = state.activeOperationMessageId,
          modifier = Modifier.weight(1f).fillMaxWidth(),
        )
      }

      SelectionContainer {
        Text(
          state.connectionMessage,
          color = theme.muted,
          fontFamily = mono,
          fontSize = 12.sp,
        )
      }

      CommandLine(
        state = state,
        theme = theme,
        onSelection = onSelection,
        onCommand = onCommand,
        onCancel = onCancel,
      )
    }
  }

  @Composable
  private fun MenuScreen(
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
  ) {
    TerminalTile(
      id = TileId(0),
      title = "MENU",
      selected = false,
      theme = theme,
      modifier = modifier,
    ) {
      SelectionContainer {
        TextBlock(
          theme,
          "[ T ] Alternar tema\n" + "[ V ] Voltar à apresentação\n\n" + "Os comandos do jogo são enviados ao servidor.\n" + "Os atalhos e ações disponíveis são apresentados pelo documento recebido.",
        )
      }
    }
  }

  @Composable
  private fun PresentationScreen(
    document: PresentationDocument,
    workspace: WorkspaceState,
    workspaceEpoch: Int,
    theme: TerminalTheme,
    activeOperationMessageId: MessageId?,
    modifier: Modifier = Modifier,
  ) {
    BoxWithConstraints(
      modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
    ) {
      @Suppress("UNUSED_EXPRESSION") workspaceEpoch

      workspace.logicalWidthDp = maxWidth.value
      workspace.logicalHeightDp = maxHeight.value

      val maximizedId = workspace.maximizedId
      val compact = workspace.isCompactMode()

      when {
        maximizedId != null -> {
          val instance = workspace.instance(maximizedId) ?: return@BoxWithConstraints
          TerminalTile(
            id = instance.id,
            title = instance.title,
            selected = true,
            theme = theme,
            modifier = Modifier.fillMaxSize(),
          ) {
            RenderViewContent(
              viewType = instance.viewType,
              document = document,
              workspace = workspace,
              theme = theme,
              activeOperationMessageId = activeOperationMessageId,
            )
          }
        }

        compact -> {
          val instance = workspace.instance(workspace.selectedId) ?: return@BoxWithConstraints
          TerminalTile(
            id = instance.id,
            title = instance.title,
            selected = true,
            theme = theme,
            modifier = Modifier.fillMaxSize(),
          ) {
            RenderViewContent(
              viewType = instance.viewType,
              document = document,
              workspace = workspace,
              theme = theme,
              activeOperationMessageId = activeOperationMessageId,
            )
          }
        }

        else -> {
          WorkspaceLayout(
            node = workspace.layoutRoot,
            workspace = workspace,
            document = document,
            theme = theme,
            activeOperationMessageId = activeOperationMessageId,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }

  @Composable
  private fun PresentationBlockContent(
    content: BlockContent,
    theme: TerminalTheme,
  ) {
    when (content) {
      is BlockContent.Text -> {
        TextBlock(
          theme,
          content.text,
          color = colorFor(content.style, theme),
        )
      }

      is BlockContent.ItemsList -> {
        val prefix = if (content.isOrdered) "" else "- "

        content.items.forEachIndexed { index, item ->
          TextBlock(
            theme,
            if (content.isOrdered) {
              "${index + 1}. $item"
            } else {
              "$prefix$item"
            },
          )
        }
      }

      is BlockContent.Choices -> {
        content.choices.forEach { choice ->
          val shortcut = choice.shortcut?.let { "[$it] " } ?: ""
          val status = if (choice.enabled) "" else " (indisponível)"

          TextBlock(
            theme,
            "$shortcut${choice.text}$status",
            color = if (choice.enabled) {
              theme.foreground
            } else {
              theme.muted
            },
          )
        }
      }

      is BlockContent.Feed -> {
        val items = if (content.ordering == FeedOrdering.REVERSE_CHRONOLOGICAL) {
          content.items.reversed()
        } else {
          content.items
        }

        items.forEach { item ->
          TextBlock(
            theme,
            item.text,
            color = colorFor(item.style, theme),
            small = true,
          )
        }
      }
    }
  }

  @Composable
  private fun RenderViewContent(
    viewType: TileViewType,
    document: PresentationDocument,
    workspace: WorkspaceState,
    theme: TerminalTheme,
    activeOperationMessageId: MessageId?,
  ) {
    when (viewType) {
      TileViewType.WORLD -> {
        val blocks = document.blocks.filter { it.region != PresentationRegion.HISTORY }

        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (blocks.isEmpty()) {
            TextBlock(
              theme,
              "Sem dados recebidos.",
              color = theme.muted,
              small = true,
            )
          } else {
            blocks.forEach { block ->
              block.title?.let {
                TextBlock(
                  theme,
                  it,
                  color = theme.accent,
                  heading = true,
                )
              }

              PresentationBlockContent(
                content = block.content,
                theme = theme,
              )
            }
          }
        }
      }

      TileViewType.HELP -> {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          TextBlock(theme, "Workspace help", color = theme.accent, heading = true)

          TextBlock(
            theme,
            "Local UI commands start with the token 'ui' (case-insensitive). " + "They never reach the game server. Any other input is treated as a game command " + "or menu shortcut and may be sent to the server.",
          )

          TextBlock(theme, "Commands", color = theme.accent, heading = true)

          TextBlock(theme, "ui help")
          TextBlock(theme, "  Open or select the help view (this tile).", small = true)

          TextBlock(theme, "ui tiles")
          TextBlock(theme, "  Open or select the workspace management view.", small = true)

          TextBlock(theme, "ui open <view>")
          TextBlock(
            theme,
            "  Open a registered view. Single-instance views (world, help, workspace, history, activity) " + "select the existing instance if active, or restore it if minimized.",
            small = true,
          )

          TextBlock(theme, "ui focus <id>")
          TextBlock(theme, "  Select the active tile with the given numeric id.", small = true)

          TextBlock(theme, "ui close [id]")
          TextBlock(
            theme,
            "  Close the given active tile, or the selected tile if id is omitted. " + "The main world tile cannot be closed. Closing a minimized tile removes it permanently.",
            small = true,
          )

          TextBlock(theme, "ui minimize [id]")
          TextBlock(
            theme,
            "  Remove the given active tile (or the selected one) from the layout and keep it minimized. " + "The main world tile cannot be minimized.",
            small = true,
          )

          TextBlock(theme, "ui restore <id>")
          TextBlock(theme, "  Restore a minimized tile into the layout and select it.", small = true)

          TextBlock(theme, "ui maximize [id]")
          TextBlock(
            theme,
            "  Maximize the given active tile (or the selected one) to fill the workspace. " + "The original layout tree is preserved.",
            small = true,
          )

          TextBlock(theme, "ui unmaximize")
          TextBlock(theme, "  Restore the layout after maximization.", small = true)

          TextBlock(theme, "ui swap <id> <other-id>")
          TextBlock(
            theme,
            "  Swap the positions of two active tiles without changing their identities or content.",
            small = true,
          )

          TextBlock(theme, "Views", color = theme.accent, heading = true)

          TextBlock(theme, "world")
          TextBlock(theme, "  Main presentation document (excludes client history).", small = true)

          TextBlock(theme, "help")
          TextBlock(theme, "  This help content (available offline).", small = true)

          TextBlock(theme, "workspace")
          TextBlock(theme, "  Lists active and minimized tiles with selection and maximization state.", small = true)

          TextBlock(theme, "history")
          TextBlock(theme, "  Local recent events feed maintained by the client.", small = true)

          TextBlock(theme, "activity")
          TextBlock(
            theme,
            "  Shows the currently active operation message id when one exists. " + "No progress percentages or estimated states are invented.",
            small = true,
          )

          TextBlock(theme, "Selection and layout rules", color = theme.accent, heading = true)

          TextBlock(
            theme,
            "There is always one selected active tile. Closing or minimizing the selected tile " + "selects the first remaining active tile in visual order. Maximization is cleared " + "before any structural change or selection change to another tile.",
          )

          TextBlock(
            theme,
            "Compact mode activates when any active tile would be smaller than 280 dp wide or " + "180 dp tall. In that case only the selected tile is shown; the tree and all " + "instances remain intact and the full layout returns when space allows.",
          )

          TextBlock(
            theme,
            "This help view is available offline and is a single-instance view.",
            color = theme.muted,
            small = true,
          )
        }
      }

      TileViewType.WORKSPACE -> {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TextBlock(theme, "Workspace", color = theme.accent, heading = true)

          val selected = workspace.selectedId
          val maximized = workspace.maximizedId
          val active = workspace.activeInstances()
          val minimized = workspace.minimizedInstances()

          TextBlock(
            theme,
            "Selected: ${selected.value}" + if (maximized != null) " · Maximized: ${maximized.value}" else "",
            color = theme.muted,
            small = true,
          )

          TextBlock(theme, "Active tiles", color = theme.accent, heading = true)

          if (active.isEmpty()) {
            TextBlock(theme, "  (none)", color = theme.muted, small = true)
          } else {
            active.forEach { tile ->
              val markers = buildString {
                if (tile.id == selected) append(" [selected]")
                if (tile.id == maximized) append(" [maximized]")
                if (tile.id == workspace.mainTileId) append(" [main]")
              }
              val typeName = TileViewRegistry.definition(tile.viewType)?.textualId ?: tile.viewType.name.lowercase()
              TextBlock(
                theme,
                "  #${tile.id.value}  $typeName  \"${tile.title}\"  ${tile.lifecycle.name.lowercase()}$markers",
                small = true,
              )
            }
          }

          TextBlock(theme, "Minimized tiles", color = theme.accent, heading = true)

          if (minimized.isEmpty()) {
            TextBlock(theme, "  (none)", color = theme.muted, small = true)
          } else {
            minimized.forEach { tile ->
              val typeName = TileViewRegistry.definition(tile.viewType)?.textualId ?: tile.viewType.name.lowercase()
              TextBlock(
                theme,
                "  #${tile.id.value}  $typeName  \"${tile.title}\"  ${tile.lifecycle.name.lowercase()}",
                small = true,
              )
            }
          }
        }
      }

      TileViewType.HISTORY -> {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          TextBlock(theme, "History", color = theme.accent, heading = true)

          val historyBlocks = document.blocks.filter { it.region == PresentationRegion.HISTORY }

          if (historyBlocks.isEmpty()) {
            TextBlock(
              theme,
              "No recent events.",
              color = theme.muted,
              small = true,
            )
          } else {
            historyBlocks.forEach { block ->
              PresentationBlockContent(
                content = block.content,
                theme = theme,
              )
            }
          }
        }
      }

      TileViewType.ACTIVITY -> {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          TextBlock(theme, "Activity", color = theme.accent, heading = true)

          val activeId = activeOperationMessageId
          if (activeId != null) {
            TextBlock(
              theme,
              "Active operation message id: ${activeId.value}",
            )
          } else {
            TextBlock(
              theme,
              "No active operation.",
              color = theme.muted,
              small = true,
            )
          }
        }
      }
    }
  }

  @Composable
  private fun WorkspaceLayout(
    node: WorkspaceLayoutNode,
    workspace: WorkspaceState,
    document: PresentationDocument,
    theme: TerminalTheme,
    activeOperationMessageId: MessageId?,
    modifier: Modifier = Modifier,
  ) {
    when (node) {
      is WorkspaceLayoutNode.Leaf -> {
        val instance = workspace.instance(node.tileId) ?: return
        TerminalTile(
          id = instance.id,
          title = instance.title,
          selected = workspace.selectedId == instance.id,
          theme = theme,
          modifier = modifier,
        ) {
          RenderViewContent(
            viewType = instance.viewType,
            document = document,
            workspace = workspace,
            theme = theme,
            activeOperationMessageId = activeOperationMessageId,
          )
        }
      }

      is WorkspaceLayoutNode.Branch -> {
        when (node.orientation) {
          SplitOrientation.ROW -> {
            Row(
              modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              WorkspaceLayout(
                node = node.first,
                workspace = workspace,
                document = document,
                theme = theme,
                activeOperationMessageId = activeOperationMessageId,
                modifier = Modifier.weight(1f).fillMaxHeight(),
              )
              WorkspaceLayout(
                node = node.second,
                workspace = workspace,
                document = document,
                theme = theme,
                activeOperationMessageId = activeOperationMessageId,
                modifier = Modifier.weight(1f).fillMaxHeight(),
              )
            }
          }

          SplitOrientation.COLUMN -> {
            Column(
              modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              WorkspaceLayout(
                node = node.first,
                workspace = workspace,
                document = document,
                theme = theme,
                activeOperationMessageId = activeOperationMessageId,
                modifier = Modifier.weight(1f).fillMaxWidth(),
              )
              WorkspaceLayout(
                node = node.second,
                workspace = workspace,
                document = document,
                theme = theme,
                activeOperationMessageId = activeOperationMessageId,
                modifier = Modifier.weight(1f).fillMaxWidth(),
              )
            }
          }
        }
      }
    }
  }

  @Composable
  private fun CommandLine(
    state: ClientPresentationState,
    theme: TerminalTheme,
    onSelection: (String) -> Unit,
    onCommand: (String) -> Unit,
    onCancel: (MessageId) -> Unit,
  ) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
      focusRequester.requestFocus()
    }

    Row(
      Modifier.fillMaxWidth().height(42.dp).border(1.dp, theme.border).padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        ":",
        color = theme.accent,
        fontSize = 15.sp,
        fontFamily = mono,
      )

      Spacer(Modifier.width(8.dp))

      BasicTextField(
        value = state.input,
        onValueChange = state::updateInput,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) {
            return@onPreviewKeyEvent false
          }

          when {
            event.isCtrlPressed && event.key == Key.Tab -> {
              if (event.isShiftPressed) {
                state.selectPreviousTile()
              } else {
                state.selectNextTile()
              }
              true
            }

            event.isAltPressed -> {
              val number = when (event.key) {
                Key.One -> 1
                Key.Two -> 2
                Key.Three -> 3
                Key.Four -> 4
                Key.Five -> 5
                Key.Six -> 6
                Key.Seven -> 7
                Key.Eight -> 8
                Key.Nine -> 9
                else -> null
              }

              if (number != null) {
                state.selectTileByNumber(number)
                true
              } else {
                false
              }
            }

            event.key == Key.DirectionUp -> state.previousCommand()
            event.key == Key.DirectionDown -> state.nextCommand()

            else -> false
          }
        }.onKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
            state.submitCommand(
              selectionSink = onSelection,
              commandSink = onCommand,
              cancelSink = onCancel,
            )
            focusRequester.requestFocus()
            true
          } else {
            false
          }
        }.focusable(),
        singleLine = true,
        textStyle = TextStyle(
          color = theme.foreground,
          fontFamily = mono,
          fontSize = 14.sp,
        ),
        cursorBrush = SolidColor(theme.accent),
        decorationBox = { inner ->
          Box {
            if (state.input.isEmpty()) {
              Text(
                "digite um comando ou atalho · M menu · Enter enviar",
                color = theme.muted,
                fontFamily = mono,
                fontSize = 14.sp,
              )
            }

            inner()
          }
        },
      )
    }
  }

  @Composable
  private fun TerminalTile(
    id: TileId,
    title: String,
    selected: Boolean,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
  ) {
    val borderColor by animateColorAsState(
      targetValue = if (selected) theme.accent else theme.border,
      animationSpec = tween(durationMillis = 150),
      label = "tileBorder",
    )

    Column(
      modifier.animateContentSize(animationSpec = tween(durationMillis = 180)).border(1.dp, borderColor)
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      SelectionContainer {
        val selectionMark = if (selected) " * " else "   "
        Text(
          "┌─ [${id.value}]$selectionMark$title",
          color = theme.accent,
          fontFamily = mono,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
      }

      Box(
        Modifier.fillMaxWidth().weight(1f, fill = false)
      ) {
        content()
      }
    }
  }

  @Composable
  private fun TextBlock(
    theme: TerminalTheme,
    text: String,
    color: Color = theme.foreground,
    heading: Boolean = false,
    small: Boolean = false,
  ) {
    Text(
      text = text,
      color = color,
      fontFamily = mono,
      fontSize = when {
        small -> 12.sp
        heading -> 16.sp
        else -> 14.sp
      },
      fontWeight = if (heading) {
        FontWeight.Bold
      } else {
        FontWeight.Normal
      },
      lineHeight = if (small) 17.sp else 22.sp,
    )
  }

  private fun colorFor(
    style: ProtocolTextStyle,
    theme: TerminalTheme,
  ): Color = when (style) {
    ProtocolTextStyle.NORMAL -> theme.foreground
    ProtocolTextStyle.HEADER, ProtocolTextStyle.HIGHLIGHT -> theme.accent
    ProtocolTextStyle.SUBHEADER -> theme.foreground
    ProtocolTextStyle.MUTED -> theme.muted
    ProtocolTextStyle.WARNING -> Color(0xFFFFC857)
    ProtocolTextStyle.ERROR -> Color(0xFFE96B6B)
  }
}

//entrypoint

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "FL-RPG · terminal",
  ) {
    val state = remember {
      ClientPresentationState()
    }

    val connection = remember(state) {
      ClientConnection(
        onServerMessage = state::receive,
        onConnectionMessage = { message ->
          state.connectionMessage = message
        },
        onOperationStarted = { messageId ->
          state.activeOperationMessageId = messageId
        },
      )
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(connection) {
      connection.connect(scope)
    }

    DisposableEffect(connection) {
      onDispose {
        connection.close()
      }
    }

    ClientUi.TerminalApp(
      state = state,
      onSelection = connection::sendSelection,
      onCommand = { text ->
        val trimmed = text.trim()

        if (trimmed.isNotEmpty()) {
          connection.sendTextCommand(trimmed)
        }
      },
      onCancel = connection::sendCancel,
    )
  }
}