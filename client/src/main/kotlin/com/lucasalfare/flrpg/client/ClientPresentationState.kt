package com.lucasalfare.flrpg.client

import androidx.compose.runtime.*
import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.communicationprotocol.TextStyle as ProtocolTextStyle

private enum class ClientScreen {
  PRESENTATION, MENU,
}

class ClientPresentationState {

  private var serverDocument by mutableStateOf(PresentationDocument())

  private val recentHistory = mutableStateListOf<FeedItem>()

  private val clientHistoryBlockId = BlockId("client_recent_history")

  var activeOperationMessageId: MessageId? by mutableStateOf(null)

  private var trackedActivityId: String? = null

  private val activityRecords = mutableStateMapOf<String, ProtocolActivity>()
  private var activityOrder by mutableStateOf<List<String>>(emptyList())

  val activities: List<ProtocolActivity>
    get() = activityOrder.mapNotNull { activityRecords[it] }

  private val pendingOperationMessageIds = mutableListOf<MessageId>()

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
  val animationSettings = WorkspaceAnimationSettings()

  var workspaceEpoch by mutableStateOf(0)
    private set

  private val commandHistory = mutableStateListOf<String>()

  private var historyIndex by mutableStateOf<Int?>(null)
  private var inputBeforeHistory = ""

  private fun touchWorkspace() {
    workspaceEpoch += 1
  }

  fun trackOperationStarted(messageId: MessageId) {
    activeOperationMessageId = messageId
    if (!pendingOperationMessageIds.contains(messageId)) {
      pendingOperationMessageIds.add(messageId)
    }
  }

  fun resetConnectionState() {
    activeOperationMessageId = null
    trackedActivityId = null
    pendingOperationMessageIds.clear()
    activityRecords.clear()
    activityOrder = emptyList()
  }

  fun receive(message: ServerMessage) {
    when (message) {
      is ServerWelcome -> {
        resetConnectionState()
        connectionMessage = if (message.isResumed) {
          "CONECTADO · sessão retoma"
        } else {
          "CONECTADO · sessão estabelecida"
        }
      }

      is ServerReject -> {
        connectionMessage = "REJEITADO · ${message.reason}"
        val targetMsgId = message.correlationId?.let { MessageId(it.value) } ?: activeOperationMessageId
        if (targetMsgId != null) {
          pendingOperationMessageIds.remove(targetMsgId)
        }
        if (activeOperationMessageId == targetMsgId) {
          activeOperationMessageId = null
        }
      }

      is ServerAccepted -> {
        connectionMessage = "ACEITE · ${message.detail}"
      }

      is ServerCompleted -> {
        val factsSummary = message.facts.joinToString { it.name }

        connectionMessage = "CONCLUÍDO · factos: $factsSummary"

        message.facts.forEach { fact ->
          appendHistory(fact.name, fact.arguments)
          processLifecycleFact(fact.name, fact.arguments)
        }

        val correlationMsgId = message.correlationId?.let { MessageId(it.value) }
        if (correlationMsgId != null) {
          pendingOperationMessageIds.remove(correlationMsgId)
          if (activeOperationMessageId == correlationMsgId && trackedActivityId == null) {
            activeOperationMessageId = null
          }
        }
      }

      is ServerEvent -> {
        connectionMessage = "EVENTO · ${message.type}"
        appendHistory(message.type, message.payload)
        processLifecycleFact(message.type, message.payload)
      }

      is PresentationSnapshot -> {
        serverDocument = message.document
        syncActivities(serverDocument)

        connectionMessage = "CONECTADO · revisão de apresentação ${serverDocument.revision.value}"
      }

      is PresentationPatch -> {
        val updatedDocument = runCatching {
          serverDocument.applyPatch(message)
        }

        updatedDocument.onSuccess {
          serverDocument = it
          syncActivities(it)

          connectionMessage = "CONECTADO · revisão de apresentação ${serverDocument.revision.value}"
        }.onFailure {
          connectionMessage = "APRESENTAÇÃO DESACTUALIZADA · snapshot necessário"
        }
      }

      is ServerRejected -> {
        connectionMessage = "SERVIDOR REJEITOU · ${message.reason}"
        val targetMsgId = message.correlationId?.let { MessageId(it.value) } ?: activeOperationMessageId
        if (targetMsgId != null) {
          pendingOperationMessageIds.remove(targetMsgId)
        }
        if (activeOperationMessageId == targetMsgId) {
          activeOperationMessageId = null
        }
      }

      is ServerFailed -> {
        connectionMessage = "FALHA NO SERVIDOR · ${message.error}"
        val targetMsgId = message.correlationId?.let { MessageId(it.value) } ?: activeOperationMessageId
        if (targetMsgId != null) {
          pendingOperationMessageIds.remove(targetMsgId)
        }
        if (activeOperationMessageId == targetMsgId) {
          activeOperationMessageId = null
        }
      }

      is ServerProtocolError -> {
        connectionMessage = "ERRO DE PROTOCOLO · ${message.description}"
      }
    }
  }

  private fun syncActivities(document: PresentationDocument) {
    val incoming =
      document.blocks.map { it.content }.filterIsInstance<BlockContent.Activities>().flatMap { it.activities }
        .associateBy { it.activityId }

    activityRecords.keys.retainAll(incoming.keys)
    incoming.forEach { (id, activity) ->
      if (activityRecords[id] != activity) activityRecords[id] = activity
    }
    activityOrder = incoming.keys.toList()
  }

  private fun processLifecycleFact(
    factName: String,
    args: Map<String, String>,
  ) {
    val activityId = args["activityId"]

    when (factName) {
      "activity_started" -> {
        if (activityId != null) {
          trackedActivityId = activityId
        }
      }

      "activity_completed", "activity_cancelled", "activity_interrupted", "activity_failed", "character_movement_completed", "character_movement_interrupted" -> {
        if (activityId != null && trackedActivityId != null) {
          if (activityId == trackedActivityId) {
            trackedActivityId = null
            activeOperationMessageId = null
          }
        } else {
          trackedActivityId = null
          activeOperationMessageId = null
        }
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

    if (handleClientCommand(command, selectionSink, cancelSink)) {
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
    selectionSink: (String) -> Unit,
    cancelSink: (MessageId) -> Unit,
  ): Boolean {
    val parts = command.trim().split(Regex("\\s+"), limit = 2)

    if (parts[0].equals("CANCEL", ignoreCase = true) || parts[0].equals("CANCELAR", ignoreCase = true)) {
      cancelActivity(parts.getOrNull(1).orEmpty(), selectionSink, cancelSink)
      return true
    }

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

      else -> false
    }
  }

  private fun cancelActivity(
    reference: String,
    selectionSink: (String) -> Unit,
    cancelSink: (MessageId) -> Unit,
  ) {
    val options = activities.mapNotNull { activity ->
      activity.cancelChoiceId?.takeIf { activity.cancellable }?.let { activity to it }
    }
    val requested = reference.trim()

    if (requested.isNotEmpty()) {
      val choiceId = options.firstOrNull { (activity, _) ->
        activity.cancelShortcut.equals(requested, ignoreCase = true)
      }?.second

      if (choiceId != null) {
        selectionSink(choiceId)
      } else {
        connectionMessage = "CANCEL · no cancellable activity with reference '$requested'"
      }
      return
    }

    when {
      options.size == 1 -> selectionSink(options.single().second)

      options.size > 1 -> {
        val references = options.mapNotNull { it.first.cancelShortcut }.joinToString(", ")
        connectionMessage = "CANCEL · multiple activities can be cancelled, use: cancel <ref> ($references)"
      }

      else -> {
        val targetId = activeOperationMessageId

        if (targetId != null) {
          cancelSink(targetId)
        } else {
          connectionMessage = "CANCEL · no active activity to cancel"
        }
      }
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

      LocalUiCommand.Menu -> {
        screen = ClientScreen.MENU
        "UI · menu opened"
      }

      LocalUiCommand.Presentation -> {
        screen = ClientScreen.PRESENTATION
        "UI · presentation view opened"
      }

      LocalUiCommand.Theme -> {
        darkThemeEnabled = !darkThemeEnabled
        "UI · theme toggled"
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

      LocalUiCommand.Animations -> {
        val newState = animationSettings.toggle()
        "UI · animations " + if (newState) "enabled" else "disabled"
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