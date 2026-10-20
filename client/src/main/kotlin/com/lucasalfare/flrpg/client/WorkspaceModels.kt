package com.lucasalfare.flrpg.client

@JvmInline
value class TileId(val value: Int)

enum class TileViewType {
  WORLD, HELP, WORKSPACE, HISTORY, ACTIVITY,
}

enum class TileLifecycle {
  ACTIVE, MINIMIZED,
}

data class TileInstance(
  val id: TileId,
  val viewType: TileViewType,
  val lifecycle: TileLifecycle,
  val title: String,
)

enum class SplitOrientation {
  ROW, COLUMN,
}

sealed class WorkspaceLayoutNode {
  data class Leaf(val tileId: TileId) : WorkspaceLayoutNode()

  data class Branch(
    val orientation: SplitOrientation,
    val first: WorkspaceLayoutNode,
    val second: WorkspaceLayoutNode,
  ) : WorkspaceLayoutNode()

  fun leafCount(): Int = when (this) {
    is Leaf -> 1
    is Branch -> first.leafCount() + second.leafCount()
  }

  fun visualLeaves(): List<TileId> = when (this) {
    is Leaf -> listOf(tileId)
    is Branch -> first.visualLeaves() + second.visualLeaves()
  }

  fun findLeaf(id: TileId): Leaf? = when (this) {
    is Leaf -> if (tileId == id) this else null
    is Branch -> first.findLeaf(id) ?: second.findLeaf(id)
  }

  fun contains(id: TileId): Boolean = findLeaf(id) != null

  fun hasDuplicateIds(): Boolean {
    val seen = mutableSetOf<TileId>()
    for (id in visualLeaves()) {
      if (!seen.add(id)) return true
    }
    return false
  }

  fun swapLeaves(a: TileId, b: TileId): WorkspaceLayoutNode = when (this) {
    is Leaf -> when (tileId) {
      a -> Leaf(b)
      b -> Leaf(a)
      else -> this
    }

    is Branch -> {
      val newFirst = first.swapLeaves(a, b)
      val newSecond = second.swapLeaves(a, b)
      if (newFirst === first && newSecond === second) this
      else copy(first = newFirst, second = newSecond)
    }
  }

  fun findParentOrientation(id: TileId, parentOrientation: SplitOrientation? = null): SplitOrientation? = when (this) {
    is Leaf -> if (tileId == id) parentOrientation else null
    is Branch -> first.findParentOrientation(id, orientation) ?: second.findParentOrientation(id, orientation)
  }
}

internal fun WorkspaceLayoutNode.replaceLeaf(
  targetId: TileId,
  replacement: WorkspaceLayoutNode,
): WorkspaceLayoutNode = when (this) {
  is WorkspaceLayoutNode.Leaf -> if (tileId == targetId) replacement else this
  is WorkspaceLayoutNode.Branch -> {
    val newFirst = first.replaceLeaf(targetId, replacement)
    val newSecond = second.replaceLeaf(targetId, replacement)
    if (newFirst === first && newSecond === second) this
    else copy(first = newFirst, second = newSecond)
  }
}

internal fun WorkspaceLayoutNode.removeLeaf(targetId: TileId): WorkspaceLayoutNode? = when (this) {
  is WorkspaceLayoutNode.Leaf -> if (tileId == targetId) null else this
  is WorkspaceLayoutNode.Branch -> {
    val newFirst = first.removeLeaf(targetId)
    val newSecond = second.removeLeaf(targetId)
    when {
      newFirst == null -> newSecond
      newSecond == null -> newFirst
      newFirst === first && newSecond === second -> this
      else -> copy(first = newFirst, second = newSecond)
    }
  }
}

data class ViewDefinition(
  val type: TileViewType,
  val textualId: String,
  val defaultTitle: String,
  val singleInstance: Boolean,
)

object TileViewRegistry {
  private val byTextualId = mapOf(
    "world" to ViewDefinition(
      type = TileViewType.WORLD,
      textualId = "world",
      defaultTitle = "World",
      singleInstance = true,
    ),
    "help" to ViewDefinition(
      type = TileViewType.HELP,
      textualId = "help",
      defaultTitle = "Help",
      singleInstance = true,
    ),
    "workspace" to ViewDefinition(
      type = TileViewType.WORKSPACE,
      textualId = "workspace",
      defaultTitle = "Workspace",
      singleInstance = true,
    ),
    "history" to ViewDefinition(
      type = TileViewType.HISTORY,
      textualId = "history",
      defaultTitle = "History",
      singleInstance = true,
    ),
    "activity" to ViewDefinition(
      type = TileViewType.ACTIVITY,
      textualId = "activity",
      defaultTitle = "Activity",
      singleInstance = true,
    ),
  )

  private val byType = byTextualId.values.associateBy { it.type }

  fun isRegistered(textualId: String): Boolean = byTextualId.containsKey(textualId.lowercase())

  fun isRegistered(type: TileViewType): Boolean = byType.containsKey(type)

  fun definition(textualId: String): ViewDefinition? = byTextualId[textualId.lowercase()]

  fun definition(type: TileViewType): ViewDefinition? = byType[type]

  fun defaultTitle(type: TileViewType): String? = byType[type]?.defaultTitle

  fun allDefinitions(): Collection<ViewDefinition> = byTextualId.values
}