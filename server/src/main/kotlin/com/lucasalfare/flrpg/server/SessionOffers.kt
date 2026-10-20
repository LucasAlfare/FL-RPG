package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.Choice
import com.lucasalfare.flrpg.communicationprotocol.PresentationDocument
import com.lucasalfare.flrpg.communicationprotocol.Revision
import com.lucasalfare.flrpg.communicationprotocol.SessionId
import com.lucasalfare.flrpg.gamedomain.Interaction
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

data class OfferedOption(
  val choiceId: String, val interaction: Interaction
)

data class PreparedPresentation(
  val choices: List<Choice>, val offered: List<OfferedOption>, val document: PresentationDocument
)

class SessionOfferState {
  val updateMutex = Mutex()

  @Volatile
  var revision: Revision = Revision.ZERO
    private set

  private val offeredByChoiceId = ConcurrentHashMap<String, Interaction>()
  private val persistentShortcuts = ConcurrentHashMap<String, String>()

  fun replaceOffers(offered: List<OfferedOption>, newRevision: Revision, choices: List<Choice> = emptyList()) {
    offeredByChoiceId.clear()
    for (option in offered) {
      offeredByChoiceId[option.choiceId] = option.interaction
    }
    if (choices.isNotEmpty()) {
      persistentShortcuts.clear()
      for (choice in choices) {
        if (choice.shortcut != null) {
          persistentShortcuts[choice.id] = choice.shortcut!!
        }
      }
    }
    revision = newRevision
  }

  fun findInteraction(choiceId: String): Interaction? = offeredByChoiceId[choiceId]

  fun clear() {
    offeredByChoiceId.clear()
    persistentShortcuts.clear()
    revision = Revision.ZERO
  }

  fun offeredSnapshot(): Map<String, Interaction> = offeredByChoiceId.toMap()

  fun getPersistentShortcuts(): Map<String, String> = persistentShortcuts.toMap()
}

class SessionOfferRegistry {
  private val states = ConcurrentHashMap<String, SessionOfferState>()

  fun getOrCreate(sessionId: SessionId): SessionOfferState = states.getOrPut(sessionId.value) { SessionOfferState() }

  fun remove(sessionId: SessionId) {
    states.remove(sessionId.value)?.clear()
  }

  fun find(sessionId: SessionId): SessionOfferState? = states[sessionId.value]
}