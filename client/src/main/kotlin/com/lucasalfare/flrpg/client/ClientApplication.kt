package com.lucasalfare.flrpg.client

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

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
          if (message.startsWith("OFFLINE") || message.startsWith("FALHA")) {
            state.resetConnectionState()
          }
        },
        onOperationStarted = { messageId ->
          state.trackOperationStarted(messageId)
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
        state.resetConnectionState()
      }
    }

    TerminalApp(
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