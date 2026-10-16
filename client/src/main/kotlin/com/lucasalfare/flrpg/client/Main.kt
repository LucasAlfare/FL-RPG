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

class ClientConnection(
  private val state: ClientPresentationState, private val serverUrl: String = "ws://localhost:8080/game"
) {
  private val client = HttpClient(CIO) {
    install(WebSockets)
  }

  private var sessionId: SessionId = SessionId(UUID.randomUUID().toString())

  private var nextSequence: Sequence = ZERO
  private var handshakeDone = false

  private var job: Job? = null
  private var session: DefaultClientWebSocketSession? = null

  fun connect(scope: CoroutineScope) {
    if (job?.isActive == true) return

    job = scope.launch {
      try {
        state.connectionMessage = "A LIGAR..."
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
        state.connectionMessage = "FALHA NA LIGAÇÃO · ${e.message}"
      } finally {
        session = null
        state.connectionMessage = "OFFLINE · sem ligação ao servidor"
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
      state.connectionMessage = "NÃO PRONTO · handshake pendente"
      return
    }

    val selection = ClientSelection(
      metadata = nextMetadata(), choiceId = choiceId
    )

    state.connectionMessage = "A ENVIAR SELEÇÃO · $choiceId (seq ${selection.sequence.value})"

    currentSession.launch {
      try {
        currentSession.send(Frame.Text(selection.toJson()))
      } catch (e: Exception) {
        state.connectionMessage = "FALHA NO ENVIO · ${e.message}"
      }
    }
  }

  fun sendTextCommand(rawText: String) {
    val currentSession = session
    if (currentSession == null || !handshakeDone) {
      state.connectionMessage = "NÃO PRONTO · handshake pendente"
      return
    }

    val textCommand = ClientTextCommand(
      metadata = nextMetadata(), rawText = rawText
    )

    state.connectionMessage = "A ENVIAR COMANDO · $rawText (seq ${textCommand.sequence.value})"

    currentSession.launch {
      try {
        currentSession.send(Frame.Text(textCommand.toJson()))
      } catch (e: Exception) {
        state.connectionMessage = "FALHA NO ENVIO · ${e.message}"
      }
    }
  }

  fun receiveFromNetwork(message: ServerMessage) {
    when (message) {
      is ServerWelcome -> {
        nextSequence = ZERO
        handshakeDone = true
        state.receive(message)
      }

      else -> state.receive(message)
    }
  }

  private fun nextMetadata(
    correlationId: CorrelationId? = null
  ): MessageMetadata {
    val metadata = MessageMetadata(
      messageId = MessageId(UUID.randomUUID().toString()),
      sessionId = sessionId,
      sequence = nextSequence,
      correlationId = correlationId
    )
    nextSequence = nextSequence.next()
    return metadata
  }

  fun close() {
    job?.cancel()
    client.close()
  }
}

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

private enum class ClientScreen { PRESENTATION, MENU }

class ClientPresentationState {
  var document by mutableStateOf(PresentationDocument())
    private set
  var darkThemeEnabled by mutableStateOf(true)
    private set
  private var screen by mutableStateOf(ClientScreen.PRESENTATION)
  val isMenuOpen: Boolean get() = screen == ClientScreen.MENU
  var input by mutableStateOf("")
    private set
  var connectionMessage by mutableStateOf("OFFLINE · sem ligação ao servidor")

  private val commandHistory = mutableStateListOf<String>()
  private var historyIndex by mutableStateOf<Int?>(null)
  private var inputBeforeHistory by mutableStateOf("")

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
      }

      is ServerAccepted -> {
        connectionMessage = "ACEITE · ${message.detail}"
      }

      is ServerCompleted -> {
        val factsSummary = message.facts.joinToString { it.name }
        connectionMessage = "CONCLUÍDO · factos: $factsSummary"
      }

      is PresentationSnapshot -> {
        document = message.document
        connectionMessage = "CONECTADO · revisão de apresentação ${document.revision.value}"
      }

      is PresentationPatch -> {
        val updatedDocument = runCatching { document.applyPatch(message) }
        updatedDocument.onSuccess {
          document = it
          connectionMessage = "CONECTADO · revisão de apresentação ${document.revision.value}"
        }.onFailure {
          connectionMessage = "APRESENTAÇÃO DESACTUALIZADA · snapshot necessário"
        }
      }

      is ServerRejected -> connectionMessage = "SERVIDOR REJEITOU · ${message.reason}"
      is ServerFailed -> connectionMessage = "FALHA NO SERVIDOR · ${message.error}"
      is ServerProtocolError -> connectionMessage = "ERRO DE PROTOCOLO · ${message.description}"
      else -> Unit
    }
  }

  fun updateInput(value: String) {
    input = value
    historyIndex = null
    inputBeforeHistory = ""
  }

  fun submitCommand(
    selectionSink: (String) -> Unit, commandSink: (String) -> Unit
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

    if (handleClientCommand(command)) {
      input = ""
      return
    }

    rememberCommand(command)
    commandSink(command)
    input = ""
  }

  fun previousCommand(): Boolean {
    if (commandHistory.isEmpty()) return false
    val nextIndex = historyIndex?.let { (it - 1).coerceAtLeast(0) } ?: kotlin.run {
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

    return activeChoices.firstOrNull { it.shortcut.equals(input, ignoreCase = true) }
      ?: activeChoices.firstOrNull { it.text.equals(input, ignoreCase = true) }
  }

  private fun handleClientCommand(command: String): Boolean {
    return when (command.uppercase()) {
      "M", "MENU" -> {
        screen = ClientScreen.MENU
        true
      }

      "V", "VOLTAR" -> {
        screen = ClientScreen.PRESENTATION
        true
      }

      "T", "TEMA" -> if (screen == ClientScreen.MENU) {
        darkThemeEnabled = !darkThemeEnabled
        true
      } else false

      else -> false
    }
  }

  private fun rememberCommand(command: String) {
    if (commandHistory.size == 20) commandHistory.removeAt(0)
    commandHistory.add(command)
    historyIndex = null
    inputBeforeHistory = ""
  }
}

private val mono = FontFamily.Monospace

fun main() = application {
  Window(onCloseRequest = ::exitApplication, title = "FL-RPG · terminal") {
    val state = remember { ClientPresentationState() }
    val scope = rememberCoroutineScope()
    val connection = remember { ClientConnection(state) }

    LaunchedEffect(Unit) {
      connection.connect(scope)
    }

    TerminalApp(state = state, onSelection = { choiceId -> connection.sendSelection(choiceId) }, onCommand = { text ->
      val trimmed = text.trim()
      if (trimmed.isEmpty()) return@TerminalApp
      connection.sendTextCommand(trimmed)
    })
  }
}

@Composable
private fun TerminalApp(
  state: ClientPresentationState, onSelection: (String) -> Unit, onCommand: (String) -> Unit
) {
  val theme = if (state.darkThemeEnabled) darkTheme else lightTheme
  Column(
    Modifier.fillMaxSize().background(theme.background).padding(horizontal = 18.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SelectionContainer {
      Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("FL-RPG", color = theme.accent, fontSize = 18.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
          Text("  ·  cliente textual", color = theme.muted, fontSize = 13.sp, fontFamily = mono)
          Spacer(Modifier.weight(1f))
          Text(
            "[ ${if (state.darkThemeEnabled) "ESCURO" else "CLARO"} ]",
            color = theme.muted,
            fontSize = 12.sp,
            fontFamily = mono
          )
        }
        Text("─".repeat(100), color = theme.border, fontSize = 12.sp, fontFamily = mono, maxLines = 1)
      }
    }

    if (state.isMenuOpen) {
      MenuScreen(theme, Modifier.weight(1f).fillMaxWidth())
    } else {
      PresentationScreen(state.document, theme, Modifier.weight(1f).fillMaxWidth())
    }

    SelectionContainer {
      Text(state.connectionMessage, color = theme.muted, fontFamily = mono, fontSize = 12.sp)
    }
    CommandLine(state, theme, onSelection, onCommand)
  }
}

@Composable
private fun MenuScreen(theme: TerminalTheme, modifier: Modifier = Modifier) {
  TerminalTile("MENU", theme, modifier) {
    SelectionContainer {
      TextBlock(
        theme,
        "[ T ] Alternar tema\n[ V ] Voltar à apresentação\n\nOs comandos do jogo são enviados ao servidor.\nOs atalhos e ações disponíveis são apresentados pelo documento recebido.",
      )
    }
  }
}

@Composable
private fun PresentationScreen(document: PresentationDocument, theme: TerminalTheme, modifier: Modifier = Modifier) {
  val mainBlocks = document.blocks.filter { it.region == PresentationRegion.MAIN }
  val sidebarBlocks = document.blocks.filter { it.region == PresentationRegion.SIDEBAR }
  val historyBlocks = document.blocks.filter { it.region == PresentationRegion.HISTORY }

  Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    PresentationRegionPane("PRINCIPAL", mainBlocks, theme, Modifier.weight(2.2f).fillMaxHeight())
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      PresentationRegionPane("BARRA LATERAL", sidebarBlocks, theme, Modifier.weight(1f))
      PresentationRegionPane("HISTÓRICO", historyBlocks, theme, Modifier.weight(1f))
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
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      if (blocks.isEmpty()) {
        TextBlock(theme, "Sem dados recebidos.", color = theme.muted, small = true)
      } else {
        blocks.forEach { block ->
          block.title?.let { TextBlock(theme, it, color = theme.accent, heading = true) }
          PresentationBlockContent(block.content, theme)
        }
      }
    }
  }
}

@Composable
private fun PresentationBlockContent(content: BlockContent, theme: TerminalTheme) {
  when (content) {
    is BlockContent.Text -> TextBlock(theme, content.text, color = colorFor(content.style, theme))
    is BlockContent.ItemsList -> {
      val prefix = if (content.isOrdered) "" else "- "
      content.items.forEachIndexed { index, item ->
        TextBlock(theme, if (content.isOrdered) "${index + 1}. $item" else "$prefix$item")
      }
    }

    is BlockContent.Choices -> content.choices.forEach { choice ->
      val shortcut = choice.shortcut?.let { "[$it] " } ?: ""
      val state = if (choice.enabled) "" else " (indisponível)"
      TextBlock(theme, "$shortcut${choice.text}$state", color = if (choice.enabled) theme.foreground else theme.muted)
    }

    is BlockContent.Feed -> {
      val items =
        if (content.ordering == FeedOrdering.REVERSE_CHRONOLOGICAL) content.items.reversed() else content.items
      items.forEach { item -> TextBlock(theme, item.text, color = colorFor(item.style, theme), small = true) }
    }
  }
}

@Composable
private fun CommandLine(
  state: ClientPresentationState, theme: TerminalTheme, onSelection: (String) -> Unit, onCommand: (String) -> Unit
) {
  Row(
    Modifier.fillMaxWidth().height(42.dp).border(1.dp, theme.border).padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(":", color = theme.accent, fontSize = 15.sp, fontFamily = mono)
    Spacer(Modifier.width(8.dp))
    BasicTextField(
      value = state.input,
      onValueChange = state::updateInput,
      modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionUp -> state.previousCommand()
          Key.DirectionDown -> state.nextCommand()
          else -> false
        }
      }.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
          state.submitCommand(onSelection, onCommand)
          true
        } else false
      }.focusable(),
      singleLine = true,
      textStyle = TextStyle(color = theme.foreground, fontFamily = mono, fontSize = 14.sp),
      cursorBrush = SolidColor(theme.accent),
      decorationBox = { inner ->
        Box {
          if (state.input.isEmpty()) Text(
            "digite um comando ou atalho · M menu · Enter enviar",
            color = theme.muted,
            fontFamily = mono,
            fontSize = 14.sp
          )
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
    verticalArrangement = Arrangement.spacedBy(7.dp)
  ) {
    SelectionContainer {
      Text("┌─ $title", color = theme.accent, fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { content() }
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
      small -> 12.sp; heading -> 16.sp; else -> 14.sp
    },
    fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
    lineHeight = if (small) 17.sp else 22.sp,
  )
}

private fun colorFor(style: ProtocolTextStyle, theme: TerminalTheme): Color = when (style) {
  ProtocolTextStyle.NORMAL -> theme.foreground
  ProtocolTextStyle.HEADER, ProtocolTextStyle.HIGHLIGHT -> theme.accent
  ProtocolTextStyle.SUBHEADER -> theme.foreground
  ProtocolTextStyle.MUTED -> theme.muted
  ProtocolTextStyle.WARNING -> Color(0xFFFFC857)
  ProtocolTextStyle.ERROR -> Color(0xFFE96B6B)
}