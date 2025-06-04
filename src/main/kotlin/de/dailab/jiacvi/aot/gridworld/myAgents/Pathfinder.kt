package de.dailab.jiacvi.aot.gridworld.myAgents

import de.dailab.jiacvi.aot.gridworld.Position
import de.dailab.jiacvi.aot.gridworld.WorkerAction
import java.util.*
import kotlin.math.abs

// Node class for A* algorithm
private data class Node(val position: Position, val parent: Node?, val g: Int, val h: Int) {
    val f: Int get() = g + h
}

object Pathfinder {

    // Heuristic function (Manhattan distance)
    private fun heuristic(a: Position, b: Position): Int {
        return abs(a.x - b.x) + abs(a.y - b.y)
    }

    /**
     * Finds the shortest path from start to goal using A* algorithm.
     */
    fun findPath(start: Position, goal: Position, obstacles: Set<Position>, gridSize: Position): Queue<WorkerAction>? {
        val openSet = PriorityQueue(compareBy(Node::f))
        val closedSet = mutableSetOf<Position>()
        val allNodes = mutableMapOf<Position, Node>()

        val startNode = Node(start, null, 0, heuristic(start, goal))
        openSet.add(startNode)
        allNodes[start] = startNode

        while (openSet.isNotEmpty()) {
            val currentNode = openSet.poll()

            if (currentNode.position == goal) {
                return reconstructPath(currentNode)
            }

            closedSet.add(currentNode.position)

            // Use a predefined set of directional actions for robustness
            val directionalActions = setOf(
                WorkerAction.NORTH, WorkerAction.SOUTH, WorkerAction.EAST, WorkerAction.WEST,
                WorkerAction.NORTHEAST, WorkerAction.NORTHWEST, WorkerAction.SOUTHEAST, WorkerAction.SOUTHWEST
            )

            for (neighborAction in directionalActions) {
                val neighborPos = currentNode.position.applyMove(neighborAction, gridSize)

                // *** THE BUG FIX IS HERE ***
                // Check that the move actually resulted in a new position and is valid.
                if (neighborPos != null && neighborPos != currentNode.position && neighborPos !in obstacles && neighborPos !in closedSet) {
                    val newG = currentNode.g + 1
                    val existingNode = allNodes[neighborPos]

                    if (existingNode == null || newG < existingNode.g) {
                        val neighborNode = Node(neighborPos, currentNode, newG, heuristic(neighborPos, goal))
                        if (existingNode != null) {
                            openSet.remove(existingNode)
                        }
                        openSet.add(neighborNode)
                        allNodes[neighborPos] = neighborNode
                    }
                }
            }
        }
        return null // No path found
    }

    private fun reconstructPath(node: Node): Queue<WorkerAction> {
        val path = LinkedList<WorkerAction>()
        var current = node
        while (current.parent != null) {
            val parentPos = current.parent!!.position
            val currentPos = current.position
            val action = getAction(parentPos, currentPos)
            path.addFirst(action)
            current = current.parent!!
        }
        return path
    }

    private fun getAction(from: Position, to: Position): WorkerAction {
        if (to.x > from.x && to.y == from.y) return WorkerAction.EAST
        if (to.x < from.x && to.y == from.y) return WorkerAction.WEST
        if (to.x == from.x && to.y > from.y) return WorkerAction.SOUTH
        if (to.x == from.x && to.y < from.y) return WorkerAction.NORTH
        if (to.x > from.x && to.y > from.y) return WorkerAction.SOUTHEAST
        if (to.x < from.x && to.y > from.y) return WorkerAction.SOUTHWEST
        if (to.x > from.x && to.y < from.y) return WorkerAction.NORTHEAST
        if (to.x < from.x && to.y < from.y) return WorkerAction.NORTHWEST
        throw IllegalArgumentException("Could not determine action from $from to $to")
    }
}