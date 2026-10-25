package com.lucasalfare.flrpg.communicationprotocol

fun PresentationDocument.applySnapshot(snapshot: PresentationSnapshot): PresentationDocument {
  return snapshot.document
}

fun PresentationDocument.applyPatch(patch: PresentationPatch): PresentationDocument {
  require(this.revision == patch.baseRevision) {
    "Cannot apply patch: current revision (${this.revision.value}) does not match patch base revision (${patch.baseRevision.value})."
  }

  val workingBlocks = this.blocks.toMutableList()

  for (op in patch.operations) {
    when (op) {
      is PatchOperation.Upsert -> {
        val index = workingBlocks.indexOfFirst { it.id == op.block.id }
        if (index != -1) {
          workingBlocks[index] = op.block
        } else {
          workingBlocks.add(op.block)
        }
      }

      is PatchOperation.Remove -> {
        val removed = workingBlocks.removeAll { it.id == op.blockId }
        require(removed) { "Cannot remove block '${op.blockId.value}': block does not exist." }
      }

      is PatchOperation.Append -> {
        require(workingBlocks.none { it.id == op.block.id }) {
          "Cannot append block '${op.block.id.value}': block ID already exists."
        }
        workingBlocks.add(op.block)
      }

      is PatchOperation.Clear -> {
        workingBlocks.clear()
      }
    }
  }

  return PresentationDocument(
    revision = patch.newRevision, blocks = workingBlocks
  )
}