package com.lucasalfare.flrpg.client

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
  WORLD, HELP, WORKSPACE, HISTORY,
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

  private fun insertIntoLargestRegion(newId: TileId) {
    val geometries = computeLeafGeometries(layoutRoot, 0f, 0f, logicalWidthDp, logicalHeightDp)
    if (geometries.isEmpty()) {
      layoutRoot = WorkspaceLayoutNode.Leaf(newId)
      return
    }

    val target = selectRegionToSplit(geometries)
    val orientation = if (target.width >= target.height) {
      SplitOrientation.ROW
    } else {
      SplitOrientation.COLUMN
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
  )

  private val byType = byTextualId.values.associateBy { it.type }

  fun isRegistered(textualId: String): Boolean = byTextualId.containsKey(textualId.lowercase())

  fun isRegistered(type: TileViewType): Boolean = byType.containsKey(type)

  fun definition(textualId: String): ViewDefinition? = byTextualId[textualId.lowercase()]

  fun definition(type: TileViewType): ViewDefinition? = byType[type]

  fun defaultTitle(type: TileViewType): String? = byType[type]?.defaultTitle

  fun allDefinitions(): Collection<ViewDefinition> = byTextualId.values
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

  private val commandHistory = mutableStateListOf<String>()

  private var historyIndex by mutableStateOf<Int?>(null)
  private var inputBeforeHistory = ""

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
          theme = theme,
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
    TerminalTile("MENU", theme, modifier) {
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
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
  ) {
    val mainBlocks = document.blocks.filter {
      it.region == PresentationRegion.MAIN
    }

    val sidebarBlocks = document.blocks.filter {
      it.region == PresentationRegion.SIDEBAR
    }

    val historyBlocks = document.blocks.filter {
      it.region == PresentationRegion.HISTORY
    }

    Row(
      modifier,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PresentationRegionPane(
        label = "PRINCIPAL",
        blocks = mainBlocks,
        theme = theme,
        modifier = Modifier.weight(2.2f).fillMaxHeight(),
      )

      Column(
        Modifier.weight(1f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PresentationRegionPane(
          label = "BARRA LATERAL",
          blocks = sidebarBlocks,
          theme = theme,
          modifier = Modifier.weight(1f),
        )

        PresentationRegionPane(
          label = "HISTÓRICO",
          blocks = historyBlocks,
          theme = theme,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }

  @Composable
  private fun PresentationRegionPane(
    label: String,
    blocks: List<PresentationBlock>,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
  ) {
    TerminalTile(label, theme, modifier) {
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
  private fun CommandLine(
    state: ClientPresentationState,
    theme: TerminalTheme,
    onSelection: (String) -> Unit,
    onCommand: (String) -> Unit,
    onCancel: (MessageId) -> Unit,
  ) {
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
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) {
            return@onPreviewKeyEvent false
          }

          when (event.key) {
            Key.DirectionUp -> state.previousCommand()
            Key.DirectionDown -> state.nextCommand()
            else -> false
          }
        }.onKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
            state.submitCommand(
              selectionSink = onSelection,
              commandSink = onCommand,
              cancelSink = onCancel,
            )

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
    title: String,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
  ) {
    Column(
      modifier.border(1.dp, theme.border).padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      SelectionContainer {
        Text(
          "┌─ $title",
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