package com.lucasalfare.flrpg.gamedomain

data class CharacterAttributes(
  val baseSpeed: SpeedCmPerSecond
) {
  val effectiveSpeed: SpeedCmPerSecond
    get() = baseSpeed

  companion object {
    val DEFAULT = CharacterAttributes(
      baseSpeed = SpeedCmPerSecond(Nucleus.DEFAULT_SPEED * LEGACY_SPEED_UNIT_CM_PER_SECOND)
    )
  }
}

data class Character(
  val id: CharacterId, val name: String, val attributes: CharacterAttributes = CharacterAttributes.DEFAULT
)