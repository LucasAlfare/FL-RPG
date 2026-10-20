package com.lucasalfare.flrpg.client

sealed class LocalUiCommand {
  data object Help : LocalUiCommand()
  data object Tiles : LocalUiCommand()
  data object Animations : LocalUiCommand()
  data object Menu : LocalUiCommand()
  data object Presentation : LocalUiCommand()
  data object Theme : LocalUiCommand()
  data class Open(val viewTextualId: String) : LocalUiCommand()
  data class Focus(val id: TileId) : LocalUiCommand()
  data class Close(val id: TileId?) : LocalUiCommand()
  data class Minimize(val id: TileId?) : LocalUiCommand()
  data class Restore(val id: TileId) : LocalUiCommand()
  data class Maximize(val id: TileId?) : LocalUiCommand()
  data object Unmaximize : LocalUiCommand()
  data class Swap(val firstId: TileId, val secondId: TileId) : LocalUiCommand()
}

sealed class UiCommandParseResult {
  data class Success(val command: LocalUiCommand) : UiCommandParseResult()
  data class Failure(val reason: String) : UiCommandParseResult()
  data object NotUiCommand : UiCommandParseResult()
}

fun parseUiCommand(input: String): UiCommandParseResult {
  val trimmed = input.trim()
  if (trimmed.isEmpty()) return UiCommandParseResult.NotUiCommand

  val tokens = trimmed.split(Regex("\\s+"))
  if (tokens.isEmpty()) return UiCommandParseResult.NotUiCommand

  if (!tokens[0].equals("ui", ignoreCase = true)) {
    return UiCommandParseResult.NotUiCommand
  }

  if (tokens.size == 1) {
    return UiCommandParseResult.Failure("missing command after 'ui'")
  }

  val verb = tokens[1].lowercase()
  val args = tokens.drop(2)

  fun parseTileId(raw: String): TileId? = raw.toIntOrNull()?.let { TileId(it) }

  return when (verb) {
    "help" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui help' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Help)
      }
    }

    "tiles" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui tiles' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Tiles)
      }
    }

    "menu" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui menu' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Menu)
      }
    }

    "presentation" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui presentation' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Presentation)
      }
    }

    "theme" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui theme' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Theme)
      }
    }

    "open" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Failure("'ui open' requires a view identifier")
        args.size > 1 -> UiCommandParseResult.Failure("'ui open' accepts only one view identifier")
        else -> {
          val viewId = args[0].lowercase()
          if (!TileViewRegistry.isRegistered(viewId)) {
            UiCommandParseResult.Failure("unknown view '$viewId'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Open(viewId))
          }
        }
      }
    }

    "focus" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Failure("'ui focus' requires a tile id")
        args.size > 1 -> UiCommandParseResult.Failure("'ui focus' accepts only one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Focus(id))
          }
        }
      }
    }

    "close" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Success(LocalUiCommand.Close(null))
        args.size > 1 -> UiCommandParseResult.Failure("'ui close' accepts at most one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Close(id))
          }
        }
      }
    }

    "minimize" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Success(LocalUiCommand.Minimize(null))
        args.size > 1 -> UiCommandParseResult.Failure("'ui minimize' accepts at most one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Minimize(id))
          }
        }
      }
    }

    "restore" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Failure("'ui restore' requires a tile id")
        args.size > 1 -> UiCommandParseResult.Failure("'ui restore' accepts only one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Restore(id))
          }
        }
      }
    }

    "maximize" -> {
      when {
        args.isEmpty() -> UiCommandParseResult.Success(LocalUiCommand.Maximize(null))
        args.size > 1 -> UiCommandParseResult.Failure("'ui maximize' accepts at most one tile id")
        else -> {
          val id = parseTileId(args[0])
          if (id == null) {
            UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
          } else {
            UiCommandParseResult.Success(LocalUiCommand.Maximize(id))
          }
        }
      }
    }

    "unmaximize" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui unmaximize' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Unmaximize)
      }
    }

    "swap" -> {
      when {
        args.size < 2 -> UiCommandParseResult.Failure("'ui swap' requires two tile ids")
        args.size > 2 -> UiCommandParseResult.Failure("'ui swap' accepts exactly two tile ids")
        else -> {
          val first = parseTileId(args[0])
          val second = parseTileId(args[1])
          when {
            first == null -> UiCommandParseResult.Failure("invalid tile id '${args[0]}'")
            second == null -> UiCommandParseResult.Failure("invalid tile id '${args[1]}'")
            first == second -> UiCommandParseResult.Failure("cannot swap a tile with itself")
            else -> UiCommandParseResult.Success(LocalUiCommand.Swap(first, second))
          }
        }
      }
    }

    "animations" -> {
      if (args.isNotEmpty()) {
        UiCommandParseResult.Failure("'ui animations' does not accept arguments")
      } else {
        UiCommandParseResult.Success(LocalUiCommand.Animations)
      }
    }

    else -> UiCommandParseResult.Failure("unknown ui command '$verb'")
  }
}