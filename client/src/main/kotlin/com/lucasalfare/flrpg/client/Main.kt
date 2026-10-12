package com.lucasalfare.flrpg.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lucasalfare.flrpg.communicationprotocol.BlockContent
import com.lucasalfare.flrpg.communicationprotocol.FeedOrdering
import com.lucasalfare.flrpg.communicationprotocol.PresentationDocument
import com.lucasalfare.flrpg.communicationprotocol.PresentationPatch
import com.lucasalfare.flrpg.communicationprotocol.PresentationRegion
import com.lucasalfare.flrpg.communicationprotocol.PresentationSnapshot
import com.lucasalfare.flrpg.communicationprotocol.ServerFailed
import com.lucasalfare.flrpg.communicationprotocol.ServerMessage
import com.lucasalfare.flrpg.communicationprotocol.ServerProtocolError
import com.lucasalfare.flrpg.communicationprotocol.ServerRejected
import com.lucasalfare.flrpg.communicationprotocol.TextStyle as ProtocolTextStyle
import com.lucasalfare.flrpg.communicationprotocol.applyPatch

private data class TerminalTheme(
  val background: Color,
  val foreground: Color,
  val muted: Color,
  val accent: Color,
  val border: Color,
)

private val darkTheme = TerminalTheme(
  background = Color(0xFF111318), foreground = Color(0xFFD7DCE5),
  muted = Color(0xFF778195), accent = Color(0xFF80C8A0), border = Color(0xFF394252),
)
private val lightTheme = TerminalTheme(
  background = Color(0xFFF4F3EE), foreground = Color(0xFF282B32),
  muted = Color(0xFF74777D), accent = Color(0xFF356C53), border = Color(0xFFB9B8B0),
)

private enum class ClientScreen { PRESENTATION, MENU }

/**
 * State owned by the client. A future network adapter calls [receive] for every server message
 * and supplies a command sink to [submitCommand]. Game state remains entirely server-owned.
 */
class ClientPresentationState {
  var document by mutableStateOf(PresentationDocument())
    private set
  var darkThemeEnabled by mutableStateOf(true)
    private set
  private var screen by mutableStateOf(ClientScreen.PRESENTATION)
  val isMenuOpen: Boolean get() = screen == ClientScreen.MENU
  var input by mutableStateOf("")
    private set
  var connectionMessage by mutableStateOf("OFFLINE · no server connection")
    private set

  private val commandHistory = mutableStateListOf<String>()
  private var historyIndex by mutableStateOf<Int?>(null)
  private var inputBeforeHistory by mutableStateOf("")

  fun receive(message: ServerMessage) {
    when (message) {
      is PresentationSnapshot -> {
        document = message.document
        connectionMessage = "CONNECTED · presentation revision ${document.revision.value}"
      }

      is PresentationPatch -> {
        val updatedDocument = runCatching { document.applyPatch(message) }
        updatedDocument.onSuccess {
          document = it
          connectionMessage = "CONNECTED · presentation revision ${document.revision.value}"
        }.onFailure {
          connectionMessage = "PRESENTATION OUT OF DATE · snapshot required"
        }
      }

      is ServerRejected -> connectionMessage = "SERVER REJECTED · ${message.reason}"
      is ServerFailed -> connectionMessage = "SERVER FAILED · ${message.error}"
      is ServerProtocolError -> connectionMessage = "PROTOCOL ERROR · ${message.description}"
      else -> Unit
    }
  }

  fun updateInput(value: String) {
    input = value
    historyIndex = null
    inputBeforeHistory = ""
  }

  fun submitCommand(commandSink: (String) -> Unit) {
    val command = input.trim()
    if (command.isEmpty()) return

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
    TerminalApp(state)
  }
}

@Composable
private fun TerminalApp(state: ClientPresentationState) {
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
            "[ ${if (state.darkThemeEnabled) "DARK" else "LIGHT"} ]",
            color = theme.muted,
            fontSize = 12.sp,
            fontFamily = mono
          )
        }
        Text("${"─".repeat(100)}", color = theme.border, fontSize = 12.sp, fontFamily = mono, maxLines = 1)
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
    CommandLine(state, theme)
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
    PresentationRegionPane("MAIN", mainBlocks, theme, Modifier.weight(2.2f).fillMaxHeight())
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      PresentationRegionPane("SIDEBAR", sidebarBlocks, theme, Modifier.weight(1f))
      PresentationRegionPane("HISTORY", historyBlocks, theme, Modifier.weight(1f))
    }
  }
}

@Composable
private fun PresentationRegionPane(
  label: String,
  blocks: List<com.lucasalfare.flrpg.communicationprotocol.PresentationBlock>,
  theme: TerminalTheme,
  modifier: Modifier = Modifier,
) {
  TerminalTile(label, theme, modifier) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      if (blocks.isEmpty()) {
        TextBlock(theme, "No data received.", color = theme.muted, small = true)
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
      val state = if (choice.enabled) "" else " (unavailable)"
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
private fun CommandLine(state: ClientPresentationState, theme: TerminalTheme) {
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
          state.submitCommand { }
          true
        } else false
      }.focusable(),
      singleLine = true,
      textStyle = TextStyle(color = theme.foreground, fontFamily = mono, fontSize = 14.sp),
      cursorBrush = SolidColor(theme.accent),
      decorationBox = { inner ->
        Box {
          if (state.input.isEmpty()) Text(
            "type a command · M menu · Enter send", color = theme.muted, fontFamily = mono, fontSize = 14.sp
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
