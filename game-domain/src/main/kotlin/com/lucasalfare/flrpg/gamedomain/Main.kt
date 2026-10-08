@file:Suppress("unused")

package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.World

@JvmInline
value class CharacterId(val value: String)

@JvmInline
value class NucleusId(val value: String)

data class Character(
  val id: CharacterId, val name: String
)

class Nucleus(
  val id: NucleusId, val world: World
) {
  private val _characters = mutableSetOf<CharacterId>()

  val characters: Set<CharacterId>
    get() = _characters

  fun registerCharacter(characterId: CharacterId) {
    _characters.add(characterId)
  }

  fun unregisterCharacter(characterId: CharacterId) {
    _characters.remove(characterId)
  }

  fun containsCharacter(characterId: CharacterId): Boolean {
    return _characters.contains(characterId)
  }
}

data class GameAction(
  val name: String, val actorId: CharacterId, val arguments: Map<String, Any?> = emptyMap()
)

data class GameFact(
  val name: String, val arguments: Map<String, Any?> = emptyMap()
)

sealed interface GameActionResult {
  val facts: List<GameFact>

  data class Success(
    override val facts: List<GameFact> = emptyList()
  ) : GameActionResult

  data class Rejected(
    val reason: String, override val facts: List<GameFact> = emptyList()
  ) : GameActionResult
}

class GameActionProcessor(val nucleus: Nucleus) {
  fun process(action: GameAction): GameActionResult {
    return when (action.name) {
      "enter_world" -> processEnterWorld(action)
      "move" -> processMove(action)
      else -> GameActionResult.Rejected("Unknown action: ${action.name}")
    }
  }

  private fun processEnterWorld(action: GameAction): GameActionResult {
    return GameActionResult.Rejected("Action 'enter_world' not yet implemented")
  }

  private fun processMove(action: GameAction): GameActionResult {
    return GameActionResult.Rejected("Action 'move' not yet implemented")
  }
}