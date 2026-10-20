package com.lucasalfare.flrpg.client

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucasalfare.flrpg.communicationprotocol.*

@Composable
fun PresentationScreen(
  document: PresentationDocument,
  workspace: WorkspaceState,
  workspaceEpoch: Int,
  theme: TerminalTheme,
  activeOperationMessageId: MessageId?,
  activities: List<ProtocolActivity>,
  animationsEnabled: Boolean,
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
          animationsEnabled = animationsEnabled,
          modifier = Modifier.fillMaxSize(),
        ) {
          RenderViewContent(
            viewType = instance.viewType,
            document = document,
            workspace = workspace,
            theme = theme,
            activeOperationMessageId = activeOperationMessageId,
            activities = activities,
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
          animationsEnabled = animationsEnabled,
          modifier = Modifier.fillMaxSize(),
        ) {
          RenderViewContent(
            viewType = instance.viewType,
            document = document,
            workspace = workspace,
            theme = theme,
            activeOperationMessageId = activeOperationMessageId,
            activities = activities,
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
          activities = activities,
          animationsEnabled = animationsEnabled,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

@Composable
fun PresentationBlockContent(
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

    is BlockContent.Activities -> {
      content.activities.forEach { activity ->
        val progressText = activity.progress?.let { p ->
          val percent = (p * 100).toInt().coerceIn(0, 100)
          val remaining = activity.remainingTimeSeconds?.let { " · remaining ${it}s" } ?: ""
          " ($percent%$remaining)"
        } ?: " (elapsed ${activity.elapsedTimeSeconds}s)"

        val cancelText = if (activity.cancellable && activity.cancelShortcut != null) {
          " [${activity.cancelShortcut}] cancel"
        } else {
          ""
        }

        TextBlock(
          theme,
          "⏳ ${activity.description}$progressText$cancelText",
          color = theme.accent,
          small = true,
        )
      }
    }
  }
}

@Composable
fun RenderViewContent(
  viewType: TileViewType,
  document: PresentationDocument,
  workspace: WorkspaceState,
  theme: TerminalTheme,
  activeOperationMessageId: MessageId?,
  activities: List<ProtocolActivity>,
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
          "Local UI commands start with the token 'ui' (case-insensitive) and are available globally. " + "Free text and world choices are accepted only when the 'world' tile is selected. " + "Activity cancellations are accepted only when the 'activity' tile is selected.",
        )

        TextBlock(theme, "Commands", color = theme.accent, heading = true)

        TextBlock(theme, "ui help")
        TextBlock(theme, "  Open or select the help view.", small = true)

        TextBlock(theme, "ui tiles")
        TextBlock(theme, "  Open or select the workspace management view.", small = true)

        TextBlock(theme, "ui menu")
        TextBlock(theme, "  Open the global menu view.", small = true)

        TextBlock(theme, "ui presentation")
        TextBlock(theme, "  Return to the presentation view.", small = true)

        TextBlock(theme, "ui theme")
        TextBlock(theme, "  Toggle between dark and light theme.", small = true)

        TextBlock(theme, "ui open <view>")
        TextBlock(
          theme,
          "  Open a registered view.",
          small = true,
        )

        TextBlock(theme, "ui focus <id>")
        TextBlock(theme, "  Select the active tile with the given numeric id.", small = true)

        TextBlock(theme, "ui close [id]")
        TextBlock(
          theme,
          "  Close the given active tile, or the selected tile if id is omitted.",
          small = true,
        )

        TextBlock(theme, "ui minimize [id]")
        TextBlock(
          theme,
          "  Minimize the given active tile or the selected one.",
          small = true,
        )

        TextBlock(theme, "ui restore <id>")
        TextBlock(theme, "  Restore a minimized tile into the layout and select it.", small = true)

        TextBlock(theme, "ui maximize [id]")
        TextBlock(
          theme,
          "  Maximize the given active tile or the selected one.",
          small = true,
        )

        TextBlock(theme, "ui unmaximize")
        TextBlock(theme, "  Restore the layout after maximization.", small = true)

        TextBlock(theme, "ui swap <id> <other-id>")
        TextBlock(
          theme,
          "  Swap the positions of two active tiles.",
          small = true,
        )

        TextBlock(theme, "Views", color = theme.accent, heading = true)

        TextBlock(theme, "world")
        TextBlock(theme, "  Main presentation document (accepts free text and choices).", small = true)

        TextBlock(theme, "help")
        TextBlock(theme, "  This help content.", small = true)

        TextBlock(theme, "workspace")
        TextBlock(theme, "  Lists active and minimized tiles.", small = true)

        TextBlock(theme, "history")
        TextBlock(theme, "  Local recent events feed.", small = true)

        TextBlock(theme, "activity")
        TextBlock(
          theme,
          "  Shows active operations and accepts cancellation commands (`cancel` / `cancelar`).",
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

        val currentActivities = activities
        if (currentActivities.isEmpty()) {
          TextBlock(
            theme,
            "No active activities.",
            color = theme.muted,
            small = true,
          )
        } else {
          currentActivities.forEach { activity ->
            ActivityEntry(activity, theme)
          }
        }

        val activeId = activeOperationMessageId
        if (activeId != null) {
          TextBlock(
            theme,
            "Active operation message id: ${activeId.value}",
            color = theme.muted,
            small = true,
          )
        }
      }
    }
  }
}

@Composable
fun ActivityEntry(
  activity: ProtocolActivity,
  theme: TerminalTheme,
) {
  Column(
    Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    TextBlock(theme, activity.description, color = theme.accent)
    TextBlock(theme, activity.detailLine(), small = true)
    activity.cancelLine()?.let { TextBlock(theme, it, color = theme.muted, small = true) }
  }
}

@Composable
fun TextBlock(
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

fun ProtocolActivity.detailLine(): String = buildList {
  add(status.lowercase().replaceFirstChar { it.uppercase() })
  if (mode == "BLOCKING") add("blocking")
  add("elapsed ${elapsedTimeSeconds}s")
  progress?.let { add("${(it * 100).toInt().coerceIn(0, 100)}%") }
  remainingTimeSeconds?.let { add("${it}s left") }
}.joinToString(" · ")

fun ProtocolActivity.cancelLine(): String? = when {
  !cancellable -> null
  cancelShortcut != null -> "Cancellable · ref [$cancelShortcut]"
  else -> "Cancellable"
}