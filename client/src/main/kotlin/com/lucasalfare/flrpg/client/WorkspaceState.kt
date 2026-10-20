package com.lucasalfare.flrpg.client

class WorkspaceState {
  private val instances = linkedMapOf<TileId, TileInstance>()
  private var nextIdValue = 1

  val mainTileId: TileId
  var selectedId: TileId
    private set
  var maximizedId: TileId? = null
    private set
  var logicalWidthDp: Float = 1280f
  var logicalHeightDp: Float = 720f
  var layoutRoot: WorkspaceLayoutNode
    private set

  init {
    val worldId = allocateId()
    val world = TileInstance(
      id = worldId,
      viewType = TileViewType.WORLD,
      lifecycle = TileLifecycle.ACTIVE,
      title = "World",
    )
    instances[worldId] = world
    mainTileId = worldId
    selectedId = worldId
    layoutRoot = WorkspaceLayoutNode.Leaf(worldId)
  }

  fun instance(id: TileId): TileInstance? = instances[id]

  fun allInstances(): Collection<TileInstance> = instances.values

  fun activeInstances(): List<TileInstance> = instances.values.filter { it.lifecycle == TileLifecycle.ACTIVE }

  fun minimizedInstances(): List<TileInstance> = instances.values.filter { it.lifecycle == TileLifecycle.MINIMIZED }

  fun isCompactMode(): Boolean {
    val geometries = computeLeafGeometries(layoutRoot, 0f, 0f, logicalWidthDp, logicalHeightDp)
    return geometries.any { it.width < MIN_TILE_WIDTH_DP || it.height < MIN_TILE_HEIGHT_DP }
  }

  fun open(viewTextualId: String): Boolean {
    val definition = TileViewRegistry.definition(viewTextualId) ?: return false

    if (definition.singleInstance) {
      val existing = instances.values.firstOrNull { it.viewType == definition.type }
      if (existing != null) {
        return when (existing.lifecycle) {
          TileLifecycle.ACTIVE -> {
            clearMaximization()
            selectedId = existing.id
            true
          }

          TileLifecycle.MINIMIZED -> restoreMinimized(existing.id)
        }
      }
    }

    val newId = allocateId()
    val newInstance = TileInstance(
      id = newId,
      viewType = definition.type,
      lifecycle = TileLifecycle.ACTIVE,
      title = definition.defaultTitle,
    )
    instances[newId] = newInstance

    clearMaximization()
    insertIntoLargestRegion(newId)
    selectedId = newId
    return true
  }

  fun minimize(id: TileId): Boolean {
    if (id == mainTileId) return false

    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(id)) return false

    val newRoot = layoutRoot.removeLeaf(id) ?: return false

    clearMaximization()
    layoutRoot = newRoot
    instances[id] = current.copy(lifecycle = TileLifecycle.MINIMIZED)

    if (selectedId == id) {
      selectedId = newRoot.visualLeaves().first()
    }

    return true
  }

  fun restore(id: TileId): Boolean = restoreMinimized(id)

  private fun restoreMinimized(id: TileId): Boolean {
    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.MINIMIZED) return false

    instances[id] = current.copy(lifecycle = TileLifecycle.ACTIVE)
    clearMaximization()
    insertIntoLargestRegion(id)
    selectedId = id
    return true
  }

  fun close(id: TileId): Boolean {
    if (id == mainTileId) return false

    val current = instances[id] ?: return false

    return when (current.lifecycle) {
      TileLifecycle.ACTIVE -> {
        if (!layoutRoot.contains(id)) return false

        val newRoot = layoutRoot.removeLeaf(id) ?: return false

        clearMaximization()
        layoutRoot = newRoot
        instances.remove(id)

        if (selectedId == id) {
          selectedId = newRoot.visualLeaves().first()
        }

        true
      }

      TileLifecycle.MINIMIZED -> {
        instances.remove(id)
        true
      }
    }
  }

  fun select(id: TileId): Boolean {
    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(id)) return false

    if (selectedId != id) {
      clearMaximization()
    }
    selectedId = id
    return true
  }

  fun selectNextInVisualOrder(): Boolean {
    val leaves = layoutRoot.visualLeaves()
    if (leaves.size <= 1) return false

    val index = leaves.indexOf(selectedId)
    if (index < 0) return false

    return select(leaves[(index + 1) % leaves.size])
  }

  fun selectPreviousInVisualOrder(): Boolean {
    val leaves = layoutRoot.visualLeaves()
    if (leaves.size <= 1) return false

    val index = leaves.indexOf(selectedId)
    if (index < 0) return false

    return select(leaves[(index - 1 + leaves.size) % leaves.size])
  }

  fun maximize(id: TileId): Boolean {
    val current = instances[id] ?: return false
    if (current.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(id)) return false

    maximizedId = id
    selectedId = id
    return true
  }

  fun unmaximize(): Boolean {
    if (maximizedId == null) return false
    maximizedId = null
    return true
  }

  fun swap(firstId: TileId, secondId: TileId): Boolean {
    if (firstId == secondId) return false

    val first = instances[firstId] ?: return false
    val second = instances[secondId] ?: return false

    if (first.lifecycle != TileLifecycle.ACTIVE) return false
    if (second.lifecycle != TileLifecycle.ACTIVE) return false
    if (!layoutRoot.contains(firstId)) return false
    if (!layoutRoot.contains(secondId)) return false

    clearMaximization()
    layoutRoot = layoutRoot.swapLeaves(firstId, secondId)
    return true
  }

  private fun insertIntoLargestRegion(newId: TileId) {
    val geometries = computeLeafGeometries(layoutRoot, 0f, 0f, logicalWidthDp, logicalHeightDp)
    if (geometries.isEmpty()) {
      layoutRoot = WorkspaceLayoutNode.Leaf(newId)
      return
    }

    val target = selectRegionToSplit(geometries)
    val parentOrientation = layoutRoot.findParentOrientation(target.tileId)
    val orientation = if (parentOrientation != null) {
      if (parentOrientation == SplitOrientation.ROW) SplitOrientation.COLUMN else SplitOrientation.ROW
    } else {
      if (target.width >= target.height) SplitOrientation.ROW else SplitOrientation.COLUMN
    }

    val replacement = WorkspaceLayoutNode.Branch(
      orientation = orientation,
      first = WorkspaceLayoutNode.Leaf(target.tileId),
      second = WorkspaceLayoutNode.Leaf(newId),
    )

    layoutRoot = layoutRoot.replaceLeaf(target.tileId, replacement)
  }

  private data class LeafGeometry(
    val tileId: TileId,
    val width: Float,
    val height: Float,
  ) {
    val area: Float get() = width * height
  }

  private fun computeLeafGeometries(
    node: WorkspaceLayoutNode,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
  ): List<LeafGeometry> = when (node) {
    is WorkspaceLayoutNode.Leaf -> listOf(LeafGeometry(node.tileId, width, height))
    is WorkspaceLayoutNode.Branch -> {
      when (node.orientation) {
        SplitOrientation.ROW -> {
          val half = width / 2f
          computeLeafGeometries(node.first, x, y, half, height) + computeLeafGeometries(
            node.second, x + half, y, half, height
          )
        }

        SplitOrientation.COLUMN -> {
          val half = height / 2f
          computeLeafGeometries(node.first, x, y, width, half) + computeLeafGeometries(
            node.second, x, y + half, width, half
          )
        }
      }
    }
  }

  private fun selectRegionToSplit(geometries: List<LeafGeometry>): LeafGeometry {
    val maxArea = geometries.maxOf { it.area }
    val candidates = geometries.filter { it.area == maxArea }

    if (candidates.size == 1) return candidates.first()

    val containingSelected = candidates.firstOrNull { it.tileId == selectedId }
    if (containingSelected != null) return containingSelected

    val visualOrder = layoutRoot.visualLeaves()
    return candidates.minBy { visualOrder.indexOf(it.tileId) }
  }

  private fun clearMaximization() {
    maximizedId = null
  }

  private fun allocateId(): TileId {
    val id = TileId(nextIdValue)
    nextIdValue += 1
    return id
  }

  companion object {
    const val MIN_TILE_WIDTH_DP = 280f
    const val MIN_TILE_HEIGHT_DP = 180f
  }
}