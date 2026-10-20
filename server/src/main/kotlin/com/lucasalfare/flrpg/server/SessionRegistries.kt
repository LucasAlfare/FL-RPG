package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.MessageId
import com.lucasalfare.flrpg.communicationprotocol.SessionId
import com.lucasalfare.flrpg.gamedomain.ActivityId
import com.lucasalfare.flrpg.gamedomain.Interaction
import java.util.concurrent.ConcurrentHashMap

class PendingInteraction(
  val interaction: Interaction,
  val collectedArguments: MutableMap<String, Any?> = mutableMapOf(),
  val remainingArguments: MutableList<String> = interaction.requiredArgumentNames.toMutableList()
)

class SessionPendingRegistry {
  private val pendingMap = ConcurrentHashMap<String, PendingInteraction>()

  fun set(sessionId: SessionId, pending: PendingInteraction) {
    pendingMap[sessionId.value] = pending
  }

  fun find(sessionId: SessionId): PendingInteraction? = pendingMap[sessionId.value]

  fun remove(sessionId: SessionId) {
    pendingMap.remove(sessionId.value)
  }
}

class OperationCorrelationRegistry {
  private val messageToActivityMap = ConcurrentHashMap<String, ActivityId>()
  private val activityToSessionMap = ConcurrentHashMap<ActivityId, SessionId>()

  fun register(messageId: MessageId, activityId: ActivityId, sessionId: SessionId) {
    messageToActivityMap[messageId.value] = activityId
    activityToSessionMap[activityId] = sessionId
  }

  fun findActivityId(messageId: MessageId): ActivityId? = messageToActivityMap[messageId.value]

  fun findSessionId(activityId: ActivityId): SessionId? = activityToSessionMap[activityId]

  fun removeByActivityId(activityId: ActivityId) {
    val sessionId = activityToSessionMap.remove(activityId)
    if (sessionId != null) {
      messageToActivityMap.entries.removeAll { it.value == activityId }
    }
  }

  fun removeBySessionId(sessionId: SessionId) {
    val removedActivities = activityToSessionMap.filterValues { sessionId == it }.keys
    for (activityId in removedActivities) {
      activityToSessionMap.remove(activityId)
    }
    messageToActivityMap.entries.removeAll { it.value in removedActivities }
  }
}

class SessionInteractionOperationRegistry {
  private val map = ConcurrentHashMap<String, MessageId>()

  fun set(sessionId: SessionId, messageId: MessageId) {
    map[sessionId.value] = messageId
  }

  fun find(sessionId: SessionId): MessageId? = map[sessionId.value]

  fun remove(sessionId: SessionId) {
    map.remove(sessionId.value)
  }
}