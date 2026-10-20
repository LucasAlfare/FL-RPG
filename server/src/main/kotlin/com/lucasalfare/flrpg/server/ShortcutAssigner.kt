package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.Choice

interface ShortcutAssigner {
  fun assign(choices: List<Choice>, existingShortcuts: Map<String, String> = emptyMap()): List<Choice>
}

class SequentialLetterShortcutAssigner : ShortcutAssigner {

  override fun assign(choices: List<Choice>, existingShortcuts: Map<String, String>): List<Choice> {
    if (choices.isEmpty()) return choices

    val used = mutableSetOf<String>()
    val preAssigned = mutableMapOf<String, String>()

    for (choice in choices) {
      val existing = choice.shortcut ?: existingShortcuts[choice.id]
      if (existing != null && choice.enabled) {
        require(used.add(existing)) {
          "Conflicting shortcut '$existing' among simultaneously selectable choices."
        }
        preAssigned[choice.id] = existing
      }
    }

    var nextIndex = 0
    return choices.map { choice ->
      val shortcut = preAssigned[choice.id] ?: run {
        val assigned = nextAvailable(used, nextIndex)
        nextIndex = assigned.second
        used.add(assigned.first)
        assigned.first
      }
      choice.copy(shortcut = shortcut)
    }.also { result ->
      validateNoConflicts(result)
    }
  }

  private fun nextAvailable(used: Set<String>, startIndex: Int): Pair<String, Int> {
    var index = startIndex
    while (true) {
      val candidate = indexToShortcut(index)
      if (candidate !in used) {
        return candidate to (index + 1)
      }
      index++
    }
  }

  private fun indexToShortcut(index: Int): String {
    require(index >= 0)
    var n = index
    val chars = mutableListOf<Char>()
    do {
      chars.add('A' + (n % 26))
      n = n / 26 - 1
    } while (n >= 0)
    return chars.reversed().joinToString("")
  }

  private fun validateNoConflicts(choices: List<Choice>) {
    val seen = mutableSetOf<String>()
    for (choice in choices) {
      val s = choice.shortcut
      if (s != null && choice.enabled) {
        require(seen.add(s)) {
          "Conflicting shortcut '$s' among simultaneously selectable choices."
        }
      }
    }
  }
}

class FixedShortcutAssigner(
  private val shortcutsByChoiceId: Map<String, String>
) : ShortcutAssigner {

  override fun assign(choices: List<Choice>, existingShortcuts: Map<String, String>): List<Choice> {
    val combined = existingShortcuts + shortcutsByChoiceId
    val result = choices.map { choice ->
      val mapped = combined[choice.id] ?: choice.shortcut
      if (mapped != null) {
        choice.copy(shortcut = mapped)
      } else {
        choice
      }
    }
    validateNoConflicts(result)
    return result
  }

  private fun validateNoConflicts(choices: List<Choice>) {
    val seen = mutableSetOf<String>()
    for (choice in choices) {
      val s = choice.shortcut
      if (s != null && choice.enabled) {
        require(seen.add(s)) {
          "Conflicting shortcut '$s' among simultaneously selectable choices."
        }
      }
    }
  }
}