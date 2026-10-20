package com.lucasalfare.flrpg.client

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucasalfare.flrpg.communicationprotocol.MessageId

@Composable
fun CommandLine(
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

  SideEffect {
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
      },
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