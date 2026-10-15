package com.lucasalfare.flrpg.client

import com.lucasalfare.flrpg.communicationprotocol.*
import kotlin.test.*

class ClientTests {

  private fun metadata(
    messageId: String = "m1",
    sessionId: String = "s1",
    sequence: Long = 0,
    correlationId: String? = null,
  ): MessageMetadata = MessageMetadata(
    messageId = MessageId(messageId),
    sessionId = SessionId(sessionId),
    sequence = Sequence(sequence),
    correlationId = correlationId?.let { CorrelationId(it) },
  )

  private fun textBlock(id: String, text: String = "text"): PresentationBlock = PresentationBlock(
    id = BlockId(id),
    content = BlockContent.Text(text),
  )

  private fun textOf(block: PresentationBlock): String = (block.content as BlockContent.Text).text

  private fun snapshot(
    document: PresentationDocument,
    messageId: String = "snap1",
    sequence: Long = 0,
  ): PresentationSnapshot = PresentationSnapshot(
    metadata = metadata(messageId = messageId, sequence = sequence),
    document = document,
  )

  private fun patch(
    baseRevision: Long,
    newRevision: Long,
    operations: List<PatchOperation> = emptyList(),
    messageId: String = "patch1",
    sequence: Long = 0,
  ): PresentationPatch = PresentationPatch(
    metadata = metadata(messageId = messageId, sequence = sequence),
    baseRevision = Revision(baseRevision),
    newRevision = Revision(newRevision),
    operations = operations,
  )

  @Test
  fun clientPresentationState_appliesSnapshot() {
    val state = ClientPresentationState()
    val document = PresentationDocument(
      revision = Revision(3),
      blocks = listOf(textBlock("a"), textBlock("b")),
    )

    state.receive(snapshot(document))

    assertEquals(3L, state.document.revision.value)
    assertEquals(listOf(BlockId("a"), BlockId("b")), state.document.blocks.map { it.id })
  }

  @Test
  fun clientPresentationState_appliesValidPatch() {
    val state = ClientPresentationState()
    state.receive(
      snapshot(
        PresentationDocument(
          revision = Revision(1),
          blocks = listOf(textBlock("a", "one")),
        ),
      ),
    )

    state.receive(
      patch(
        baseRevision = 1,
        newRevision = 2,
        operations = listOf(PatchOperation.Upsert(textBlock("a", "two"))),
      ),
    )

    assertEquals(2L, state.document.revision.value)
    val block = state.document.blocks.single { it.id == BlockId("a") }
    assertEquals("two", textOf(block))
  }

  @Test
  fun clientPresentationState_rejectsStalePatchWithoutChangingDocument() {
    val state = ClientPresentationState()
    state.receive(
      snapshot(
        PresentationDocument(
          revision = Revision(5),
          blocks = listOf(textBlock("a", "original")),
        ),
      ),
    )

    state.receive(
      patch(
        baseRevision = 3,
        newRevision = 4,
        operations = listOf(PatchOperation.Upsert(textBlock("a", "stale"))),
      ),
    )

    assertEquals(5L, state.document.revision.value)
    val block = state.document.blocks.single { it.id == BlockId("a") }
    assertEquals("original", textOf(block))
  }

  @Test
  fun clientPresentationState_rejectsInvalidPatchWithoutPartialChanges() {
    val state = ClientPresentationState()
    state.receive(
      snapshot(
        PresentationDocument(
          revision = Revision(1),
          blocks = listOf(textBlock("a", "original")),
        ),
      ),
    )

    state.receive(
      patch(
        baseRevision = 1,
        newRevision = 2,
        operations = listOf(
          PatchOperation.Upsert(textBlock("a", "changed")),
          PatchOperation.Remove(BlockId("missing")),
        ),
      ),
    )

    assertEquals(1L, state.document.revision.value)
    val block = state.document.blocks.single { it.id == BlockId("a") }
    assertEquals("original", textOf(block))
  }

  @Test
  fun clientPresentationState_ignoresBlankCommand() {
    val state = ClientPresentationState()
    val received = mutableListOf<String>()

    state.updateInput("")
    state.submitCommand { received.add(it) }

    state.updateInput("   ")
    state.submitCommand { received.add(it) }

    assertTrue(received.isEmpty())
  }

  @Test
  fun clientPresentationState_restoresPreviousAndNextCommands() {
    val state = ClientPresentationState()
    val received = mutableListOf<String>()

    state.updateInput("look")
    state.submitCommand { received.add(it) }
    state.updateInput("north")
    state.submitCommand { received.add(it) }
    state.updateInput("south")
    state.submitCommand { received.add(it) }

    assertEquals(listOf("look", "north", "south"), received)
    assertEquals("", state.input)

    assertTrue(state.previousCommand())
    assertEquals("south", state.input)

    assertTrue(state.previousCommand())
    assertEquals("north", state.input)

    assertTrue(state.previousCommand())
    assertEquals("look", state.input)

    assertTrue(state.previousCommand())
    assertEquals("look", state.input)

    assertTrue(state.nextCommand())
    assertEquals("north", state.input)

    assertTrue(state.nextCommand())
    assertEquals("south", state.input)

    assertTrue(state.nextCommand())
    assertEquals("", state.input)
  }

  @Test
  fun clientPresentationState_handlesLocalMenuCommandsLocally() {
    val state = ClientPresentationState()
    val received = mutableListOf<String>()

    assertFalse(state.isMenuOpen)

    state.updateInput("M")
    state.submitCommand { received.add(it) }
    assertTrue(state.isMenuOpen)
    assertTrue(received.isEmpty())

    val themeBeforeToggle = state.darkThemeEnabled
    state.updateInput("T")
    state.submitCommand { received.add(it) }
    assertNotEquals(themeBeforeToggle, state.darkThemeEnabled)
    assertTrue(received.isEmpty())

    state.updateInput("V")
    state.submitCommand { received.add(it) }
    assertFalse(state.isMenuOpen)
    assertTrue(received.isEmpty())

    state.updateInput("T")
    state.submitCommand { received.add(it) }
    assertEquals(listOf("T"), received)
  }
}