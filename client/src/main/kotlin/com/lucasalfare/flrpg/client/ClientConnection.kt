package com.lucasalfare.flrpg.client

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