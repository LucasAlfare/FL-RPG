package com.lucasalfare.flrpg.client

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

class WorkspaceAnimationSettings {
  var enabled by mutableStateOf(true)

  fun toggle(): Boolean {
    enabled = !enabled
    return enabled
  }
}

@Composable
fun Modifier.tileAnimation(
  tileId: TileId, enabled: Boolean
): Modifier {
  if (!enabled) return this

  val transitionState = remember { MutableTransitionState(false).apply { targetState = true } }
  val transition = rememberTransition(transitionState, label = "tileAppearance")

  val scale by transition.animateFloat(
    transitionSpec = {
      spring(
        dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium
      )
    }, label = "tileScale"
  ) { if (it) 1f else 0.85f }

  val alpha by transition.animateFloat(
    transitionSpec = { tween(durationMillis = 220) }, label = "tileAlpha"
  ) { if (it) 1f else 0f }

  return this.graphicsLayer(
    scaleX = scale, scaleY = scale, alpha = alpha
  )
}