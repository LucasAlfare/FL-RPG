package com.lucasalfare.flrpg.communicationprotocol

class StructuralValidator(val limits: ProtocolLimits = ProtocolLimits()) {

  fun validate(message: ProtocolMessage): ValidationResult {
    if (message.messageId.value.isBlank()) return invalid("MessageId cannot be blank.")
    if (message.sessionId.value.isBlank()) return invalid("SessionId cannot be blank.")
    message.correlationId?.let {
      if (it.value.isBlank()) return invalid("CorrelationId cannot be blank.")
    }

    return when (message) {
      is ClientHello -> ValidationResult.Valid
      is ServerWelcome -> ValidationResult.Valid
      is ServerReject -> ValidationResult.Valid
      is ClientAction -> validateAction(message)
      is ClientSelection -> validateSelection(message)
      is ClientTextCommand -> validateTextCommand(message)
      is ClientCancel -> ValidationResult.Valid
      is ClientResyncRequest -> ValidationResult.Valid
      is ServerAccepted -> validateAccepted(message)
      is ServerRejected -> validateRejected(message)
      is ServerCompleted -> validateCompleted(message)
      is ServerFailed -> validateFailed(message)
      is ServerProtocolError -> validateProtocolError(message)
      is ServerEvent -> validateEvent(message)
      is PresentationSnapshot -> validateDocument(message.document)
      is PresentationPatch -> validatePatch(message)
    }
  }

  fun validateDocument(document: PresentationDocument): ValidationResult {
    if (document.blocks.size > limits.maxBlocksPerDocument) {
      return invalid("Document exceeds maximum block count of ${limits.maxBlocksPerDocument}.")
    }
    val blockIds = mutableSetOf<String>()
    for (block in document.blocks) {
      if (block.id.value.isBlank()) return invalid("BlockId cannot be blank.")
      if (!blockIds.add(block.id.value)) return invalid("Duplicate block ID '${block.id.value}'.")
      if (block.title != null && block.title.length > limits.maxTextLength) {
        return invalid("Block title length exceeds maximum of ${limits.maxTextLength}.")
      }
      val contentResult = validateBlockContent(block.content)
      if (!contentResult.isValid) return contentResult
    }
    return ValidationResult.Valid
  }

  fun validatePatch(patch: PresentationPatch): ValidationResult {
    if (patch.newRevision <= patch.baseRevision) {
      return invalid("Patch newRevision must be greater than baseRevision.")
    }
    if (patch.operations.size > limits.maxPatchOperations) {
      return invalid("Patch exceeds maximum operations count of ${limits.maxPatchOperations}.")
    }
    for (op in patch.operations) {
      when (op) {
        is PatchOperation.Upsert -> {
          val res = validateBlockContent(op.block.content)
          if (!res.isValid) return res
        }

        is PatchOperation.Append -> {
          val res = validateBlockContent(op.block.content)
          if (!res.isValid) return res
        }

        is PatchOperation.Remove -> {
          if (op.blockId.value.isBlank()) return invalid("BlockId cannot be blank.")
        }

        is PatchOperation.Clear -> {}
      }
    }
    return ValidationResult.Valid
  }

  private fun validateBlockContent(content: BlockContent): ValidationResult {
    return when (content) {
      is BlockContent.Text -> {
        if (content.text.length > limits.maxTextLength) {
          invalid("Text length exceeds maximum of ${limits.maxTextLength}.")
        } else ValidationResult.Valid
      }

      is BlockContent.ItemsList -> {
        if (content.items.size > limits.maxItemsPerList) {
          return invalid("Items list size exceeds maximum of ${limits.maxItemsPerList}.")
        }
        for (item in content.items) {
          if (item.length > limits.maxTextLength) {
            return invalid("List item length exceeds maximum of ${limits.maxTextLength}.")
          }
        }
        ValidationResult.Valid
      }

      is BlockContent.Choices -> {
        if (content.choices.size > limits.maxChoicesPerBlock) {
          return invalid("Choices count exceeds maximum of ${limits.maxChoicesPerBlock}.")
        }
        val seenIds = mutableSetOf<String>()
        val seenShortcuts = mutableSetOf<String>()
        for (choice in content.choices) {
          if (choice.id.isBlank()) return invalid("Choice ID cannot be blank.")
          if (!seenIds.add(choice.id)) {
            return invalid("Duplicate choice ID '${choice.id}' within the same Choices block.")
          }
          if (choice.text.isBlank()) return invalid("Choice text cannot be blank.")
          if (choice.text.length > limits.maxTextLength) {
            return invalid("Choice text length exceeds maximum of ${limits.maxTextLength}.")
          }
          if (choice.shortcut != null) {
            if (choice.shortcut.isBlank()) {
              return invalid("Choice shortcut cannot be blank when supplied.")
            }
            if (choice.shortcut.length > limits.maxTextLength) {
              return invalid("Choice shortcut length exceeds maximum of ${limits.maxTextLength}.")
            }
            if (!seenShortcuts.add(choice.shortcut)) {
              return invalid("Duplicate choice shortcut '${choice.shortcut}' within the same Choices block.")
            }
          }
        }
        ValidationResult.Valid
      }

      is BlockContent.Feed -> {
        if (content.feedId.isBlank()) return invalid("Feed ID cannot be blank.")
        if (content.items.size > limits.maxItemsPerList) {
          return invalid("Feed items count exceeds maximum of ${limits.maxItemsPerList}.")
        }
        for (item in content.items) {
          if (item.id.isBlank()) return invalid("Feed item ID cannot be blank.")
          if (item.text.length > limits.maxTextLength) {
            return invalid("Feed item text length exceeds maximum of ${limits.maxTextLength}.")
          }
        }
        ValidationResult.Valid
      }

      is BlockContent.Activities -> {
        if (content.activities.size > limits.maxItemsPerList) {
          return invalid("Activities count exceeds maximum of ${limits.maxItemsPerList}.")
        }
        for (activity in content.activities) {
          if (activity.activityId.isBlank()) return invalid("Activity ID cannot be blank.")
          if (activity.description.isBlank()) return invalid("Activity description cannot be blank.")
          if (activity.description.length > limits.maxTextLength) {
            return invalid("Activity description length exceeds maximum of ${limits.maxTextLength}.")
          }
          if (activity.status.isBlank()) return invalid("Activity status cannot be blank.")
          if (activity.mode.isBlank()) return invalid("Activity mode cannot be blank.")
          activity.cancelChoiceId?.let {
            if (it.isBlank()) return invalid("Cancel choice ID cannot be blank.")
            if (it.length > limits.maxTextLength) return invalid("Cancel choice ID length exceeds maximum.")
          }
          activity.cancelShortcut?.let {
            if (it.isBlank()) return invalid("Cancel shortcut cannot be blank.")
            if (it.length > limits.maxTextLength) return invalid("Cancel shortcut length exceeds maximum.")
          }
        }
        ValidationResult.Valid
      }
    }
  }

  private fun validateAction(action: ClientAction): ValidationResult {
    if (action.actionId.isBlank()) return invalid("Action ID cannot be blank.")
    if (action.parameters.size > limits.maxParametersPerAction) {
      return invalid("Parameters count exceeds maximum of ${limits.maxParametersPerAction}.")
    }
    for ((k, v) in action.parameters) {
      if (k.length > limits.maxTextLength || v.length > limits.maxTextLength) {
        return invalid("Action parameter length exceeds maximum of ${limits.maxTextLength}.")
      }
    }
    return ValidationResult.Valid
  }

  private fun validateSelection(selection: ClientSelection): ValidationResult {
    if (selection.choiceId.isBlank()) return invalid("Choice ID cannot be blank.")
    return ValidationResult.Valid
  }

  private fun validateTextCommand(command: ClientTextCommand): ValidationResult {
    if (command.rawText.isBlank()) return invalid("Raw text cannot be blank.")
    if (command.rawText.length > limits.maxTextLength) {
      return invalid("Text command length exceeds maximum of ${limits.maxTextLength}.")
    }
    return ValidationResult.Valid
  }

  private fun validateAccepted(accepted: ServerAccepted): ValidationResult {
    accepted.detail?.let {
      if (it.length > limits.maxTextLength) return invalid("Detail length exceeds maximum of ${limits.maxTextLength}.")
    }
    return ValidationResult.Valid
  }

  private fun validateRejected(rejected: ServerRejected): ValidationResult {
    if (rejected.reason.isBlank()) return invalid("Reason cannot be blank.")
    if (rejected.reason.length > limits.maxTextLength) return invalid("Reason length exceeds maximum of ${limits.maxTextLength}.")
    return ValidationResult.Valid
  }

  private fun validateCompleted(completed: ServerCompleted): ValidationResult {
    for (fact in completed.facts) {
      if (fact.name.isBlank()) return invalid("Fact name cannot be blank.")
      for ((k, v) in fact.arguments) {
        if (k.length > limits.maxTextLength || v.length > limits.maxTextLength) {
          return invalid("Fact argument length exceeds maximum of ${limits.maxTextLength}.")
        }
      }
    }
    return ValidationResult.Valid
  }

  private fun validateFailed(failed: ServerFailed): ValidationResult {
    if (failed.error.isBlank()) return invalid("Error message cannot be blank.")
    if (failed.error.length > limits.maxTextLength) return invalid("Error message length exceeds maximum of ${limits.maxTextLength}.")
    return ValidationResult.Valid
  }

  private fun validateProtocolError(error: ServerProtocolError): ValidationResult {
    if (error.code.isBlank()) return invalid("Code cannot be blank.")
    if (error.description.isBlank()) return invalid("Description cannot be blank.")
    if (error.description.length > limits.maxTextLength) return invalid("Description length exceeds maximum of ${limits.maxTextLength}.")
    return ValidationResult.Valid
  }

  private fun validateEvent(event: ServerEvent): ValidationResult {
    if (event.type.isBlank()) return invalid("Event type cannot be blank.")
    for ((k, v) in event.payload) {
      if (k.length > limits.maxTextLength || v.length > limits.maxTextLength) {
        return invalid("Payload entry length exceeds maximum of ${limits.maxTextLength}.")
      }
    }
    return ValidationResult.Valid
  }

  private fun invalid(message: String) = ValidationResult.Invalid(ValidationError.Structural(message))
}