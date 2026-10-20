package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.ActivityProgressDetails
import com.lucasalfare.flrpg.gamedomain.Interaction
import java.util.*

internal val DefaultPreferredInteractionShortcuts: Map<String, String> = mapOf(
  "move" to "M"
)

class InteractionPresentationPreparer(
  private val shortcutAssigner: ShortcutAssigner = SequentialLetterShortcutAssigner(),
  private val choicesBlockId: BlockId = BlockId("available_interactions"),
  private val preferredShortcutsByInteractionId: Map<String, String> = emptyMap()
) {

  fun prepare(
    interactions: List<Interaction>,
    baseRevision: Revision = Revision.ZERO,
    existingShortcuts: Map<String, String> = emptyMap()
  ): PreparedPresentation {
    val offered = interactions.map { interaction ->
      OfferedOption(
        choiceId = selectionIdFor(interaction), interaction = interaction
      )
    }

    val rawChoices = offered.map { option ->
      Choice(
        id = option.choiceId,
        text = option.interaction.label,
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value] ?: existingShortcuts[option.choiceId],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices, existingShortcuts)
    val newRevision = baseRevision.next()
    val document = PresentationDocument(
      revision = newRevision, blocks = listOf(
        PresentationBlock(
          id = choicesBlockId,
          content = BlockContent.Choices(choices = choices),
          title = "Available actions",
          region = PresentationRegion.MAIN
        )
      )
    )

    return PreparedPresentation(
      choices = choices, offered = offered, document = document
    )
  }

  fun prepareWithLocation(
    interactions: List<Interaction>,
    locationName: String?,
    movementDestinationName: String?,
    activityProgressDetails: List<ActivityProgressDetails> = emptyList(),
    baseRevision: Revision = Revision.ZERO,
    existingShortcuts: Map<String, String> = emptyMap()
  ): PreparedPresentation {
    val offered = interactions.map { interaction ->
      OfferedOption(
        choiceId = selectionIdFor(interaction), interaction = interaction
      )
    }

    val rawChoices = offered.map { option ->
      Choice(
        id = option.choiceId,
        text = option.interaction.label,
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value] ?: existingShortcuts[option.choiceId],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices, existingShortcuts)
    val newRevision = baseRevision.next()

    val contextBlocks = mutableListOf<PresentationBlock>()
    val contextText = when {
      movementDestinationName != null && locationName != null -> "Travelling from $locationName to $movementDestinationName."

      movementDestinationName != null -> "Travelling to $movementDestinationName."

      locationName != null -> "You are at $locationName."

      else -> null
    }

    if (contextText != null) {
      contextBlocks.add(
        PresentationBlock(
          id = BlockId("current_location"),
          content = BlockContent.Text(text = contextText, style = TextStyle.NORMAL),
          title = "Current location",
          region = PresentationRegion.MAIN
        )
      )
    }

    if (activityProgressDetails.isNotEmpty()) {
      val protocolActivities = activityProgressDetails.map { details ->
        val cancelOption = offered.firstOrNull { option ->
          option.interaction.actionName == "cancel_activity" && option.interaction.fixedArguments["activityId"] == details.activityId.value
        }
        val matchingChoice = cancelOption?.let { opt -> choices.firstOrNull { it.id == opt.choiceId } }

        ProtocolActivity(
          activityId = details.activityId.value,
          description = details.description,
          status = "RUNNING",
          mode = details.mode.name,
          progress = details.progress,
          remainingTimeSeconds = details.remainingTime?.value,
          elapsedTimeSeconds = details.elapsedTime.value,
          cancellable = cancelOption != null,
          cancelChoiceId = cancelOption?.choiceId,
          cancelShortcut = matchingChoice?.shortcut
        )
      }

      contextBlocks.add(
        PresentationBlock(
          id = BlockId("activities"),
          content = BlockContent.Activities(activities = protocolActivities),
          title = "Current activities",
          region = PresentationRegion.MAIN
        )
      )
    }

    contextBlocks.add(
      PresentationBlock(
        id = choicesBlockId,
        content = BlockContent.Choices(choices = choices),
        title = "Available actions",
        region = PresentationRegion.MAIN
      )
    )

    val document = PresentationDocument(
      revision = newRevision, blocks = contextBlocks
    )

    return PreparedPresentation(
      choices = choices, offered = offered, document = document
    )
  }

  fun preparePromptWithLocation(
    promptText: String,
    locationName: String?,
    movementDestinationName: String?,
    activityProgressDetails: List<ActivityProgressDetails> = emptyList(),
    cancelInteractions: List<Interaction> = emptyList(),
    baseRevision: Revision = Revision.ZERO,
    existingShortcuts: Map<String, String> = emptyMap()
  ): PreparedPresentation {
    val offered = cancelInteractions.map { interaction ->
      OfferedOption(
        choiceId = selectionIdFor(interaction), interaction = interaction
      )
    }

    val rawChoices = offered.map { option ->
      Choice(
        id = option.choiceId,
        text = option.interaction.label,
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value] ?: existingShortcuts[option.choiceId],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices, existingShortcuts)
    val newRevision = baseRevision.next()

    val contextBlocks = mutableListOf<PresentationBlock>()
    val contextText = when {
      movementDestinationName != null && locationName != null -> "Travelling from $locationName to $movementDestinationName."
      movementDestinationName != null -> "Travelling to $movementDestinationName."
      locationName != null -> "You are at $locationName."
      else -> null
    }

    if (contextText != null) {
      contextBlocks.add(
        PresentationBlock(
          id = BlockId("current_location"),
          content = BlockContent.Text(text = contextText, style = TextStyle.NORMAL),
          title = "Current location",
          region = PresentationRegion.MAIN
        )
      )
    }

    if (activityProgressDetails.isNotEmpty()) {
      val protocolActivities = activityProgressDetails.map { details ->
        val cancelOption = offered.firstOrNull { option ->
          option.interaction.actionName == "cancel_activity" && option.interaction.fixedArguments["activityId"] == details.activityId.value
        }
        val matchingChoice = cancelOption?.let { opt -> choices.firstOrNull { it.id == opt.choiceId } }

        ProtocolActivity(
          activityId = details.activityId.value,
          description = details.description,
          status = "RUNNING",
          mode = details.mode.name,
          progress = details.progress,
          remainingTimeSeconds = details.remainingTime?.value,
          elapsedTimeSeconds = details.elapsedTime.value,
          cancellable = cancelOption != null,
          cancelChoiceId = cancelOption?.choiceId,
          cancelShortcut = matchingChoice?.shortcut
        )
      }

      contextBlocks.add(
        PresentationBlock(
          id = BlockId("activities"),
          content = BlockContent.Activities(activities = protocolActivities),
          title = "Current activities",
          region = PresentationRegion.MAIN
        )
      )
    }

    contextBlocks.add(
      PresentationBlock(
        id = BlockId("prompt"),
        content = BlockContent.Text(text = promptText, style = TextStyle.HEADER),
        title = "Input required",
        region = PresentationRegion.MAIN
      )
    )

    if (choices.isNotEmpty()) {
      contextBlocks.add(
        PresentationBlock(
          id = choicesBlockId,
          content = BlockContent.Choices(choices = choices),
          title = "Available actions",
          region = PresentationRegion.MAIN
        )
      )
    }

    val document = PresentationDocument(
      revision = newRevision, blocks = contextBlocks
    )

    return PreparedPresentation(
      choices = choices, offered = offered, document = document
    )
  }

  fun preparePrompt(promptText: String, baseRevision: Revision): PresentationDocument {
    val newRevision = baseRevision.next()
    return PresentationDocument(
      revision = newRevision, blocks = listOf(
        PresentationBlock(
          id = BlockId("prompt"),
          content = BlockContent.Text(text = promptText, style = TextStyle.HEADER),
          title = "Input required",
          region = PresentationRegion.MAIN
        )
      )
    )
  }

  fun toSnapshot(
    sessionId: SessionId, prepared: PreparedPresentation, sequence: Sequence = Sequence.ZERO
  ): PresentationSnapshot {
    return PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId(UUID.randomUUID().toString()), sessionId = sessionId, sequence = sequence
      ), document = prepared.document
    )
  }

  private fun selectionIdFor(interaction: Interaction): String = "opt_${interaction.id.value}"
}