package com.lucasalfare.flrpg.client

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.lucasalfare.flrpg.communicationprotocol.TextStyle as ProtocolTextStyle

data class TerminalTheme(
  val background: Color,
  val foreground: Color,
  val muted: Color,
  val accent: Color,
  val border: Color,
)

val darkTheme = TerminalTheme(
  background = Color(0xFF111318),
  foreground = Color(0xFFD7DCE5),
  muted = Color(0xFF778195),
  accent = Color(0xFF80C8A0),
  border = Color(0xFF394252),
)

val lightTheme = TerminalTheme(
  background = Color(0xFFF4F3EE),
  foreground = Color(0xFF282B32),
  muted = Color(0xFF74777D),
  accent = Color(0xFF356C53),
  border = Color(0xFFB9B8B0),
)

internal val mono = FontFamily.Monospace

internal fun colorFor(
  style: ProtocolTextStyle,
  theme: TerminalTheme,
): Color = when (style) {
  ProtocolTextStyle.NORMAL -> theme.foreground
  ProtocolTextStyle.HEADER, ProtocolTextStyle.HIGHLIGHT -> theme.accent
  ProtocolTextStyle.SUBHEADER -> theme.foreground
  ProtocolTextStyle.MUTED -> theme.muted
  ProtocolTextStyle.WARNING -> Color(0xFFFFC857)
  ProtocolTextStyle.ERROR -> Color(0xFFE96B6B)
}