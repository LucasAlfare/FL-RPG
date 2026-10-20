package com.lucasalfare.flrpg.gamedomain

class CancelActivityHandler : GameActionHandler {
  override val actionName: String = "cancel_activity"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    val activityIdValue = action.arguments["activityId"] as? String ?: return GameActionResult.Rejected(
      "Missing activityId argument"
    )
    return nucleus.cancelActivity(action.actorId, ActivityId(activityIdValue))
  }
}