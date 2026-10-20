package com.lucasalfare.flrpg.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucasalfare.flrpg.communicationprotocol.MessageId

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
        animationsEnabled = state.animationSettings.enabled,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
    } else {
      PresentationScreen(
        document = state.document,
        workspace = state.workspace,
        workspaceEpoch = state.workspaceEpoch,
        theme = theme,
        activeOperationMessageId = state.activeOperationMessageId,
        activities = state.activities,
        animationsEnabled = state.animationSettings.enabled, // <-- Passando aqui
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
  animationsEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  TerminalTile(
    id = TileId(0),
    title = "MENU",
    selected = false,
    theme = theme,
    animationsEnabled = animationsEnabled,
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