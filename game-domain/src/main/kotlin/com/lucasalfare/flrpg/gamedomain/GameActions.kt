package com.lucasalfare.flrpg.gamedomain

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

  data class ActivityStarted(
    val activityId: ActivityId, override val facts: List<GameFact> = emptyList()
  ) : GameActionResult

  data class Rejected(
    val reason: String, override val facts: List<GameFact> = emptyList()
  ) : GameActionResult
}

data class GameFactAudience(
  val fact: GameFact, val characterIds: List<CharacterId>
)

data class GameActionExecution(
  val result: GameActionResult, val factAudiences: List<GameFactAudience>
)

interface GameActionHandler {
  val actionName: String
  fun execute(action: GameAction, nucleus: Nucleus): GameActionResult
}