package com.lucasalfare.flrpg.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucasalfare.flrpg.communicationprotocol.MessageId
import com.lucasalfare.flrpg.communicationprotocol.PresentationDocument
import com.lucasalfare.flrpg.communicationprotocol.ProtocolActivity

@Composable
internal fun WorkspaceLayout(
  node: WorkspaceLayoutNode,
  workspace: WorkspaceState,
  document: PresentationDocument,
  theme: TerminalTheme,
  activeOperationMessageId: MessageId?,
  activities: List<ProtocolActivity>,
  animationsEnabled: Boolean,
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
        animationsEnabled = animationsEnabled,
        modifier = modifier,
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
              activities = activities,
              animationsEnabled = animationsEnabled,
              modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            WorkspaceLayout(
              node = node.second,
              workspace = workspace,
              document = document,
              theme = theme,
              activeOperationMessageId = activeOperationMessageId,
              activities = activities,
              animationsEnabled = animationsEnabled,
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
              activities = activities,
              animationsEnabled = animationsEnabled,
              modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            WorkspaceLayout(
              node = node.second,
              workspace = workspace,
              document = document,
              theme = theme,
              activeOperationMessageId = activeOperationMessageId,
              activities = activities,
              animationsEnabled = animationsEnabled,
              modifier = Modifier.weight(1f).fillMaxWidth(),
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun TerminalTile(
  id: TileId,
  title: String,
  selected: Boolean,
  theme: TerminalTheme,
  animationsEnabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val borderColor by animateColorAsState(
    targetValue = if (selected) theme.accent else theme.border,
    animationSpec = tween(durationMillis = 150),
    label = "tileBorder",
  )

  Column(
    modifier = modifier.tileAnimation(tileId = id, enabled = animationsEnabled)
      .animateContentSize(animationSpec = tween(durationMillis = 180)).border(1.dp, borderColor)
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