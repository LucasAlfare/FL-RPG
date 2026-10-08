@file:Suppress("unused")

package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.World

// ============================================================================
// Identificadores de Domínio
// ============================================================================

@JvmInline
value class CharacterId(val value: String)

@JvmInline
value class NucleusId(val value: String)

// ============================================================================
// Entidades e Estruturas de Domínio
// ============================================================================

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