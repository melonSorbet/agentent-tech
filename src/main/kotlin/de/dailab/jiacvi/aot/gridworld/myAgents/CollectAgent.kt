package de.dailab.jiacvi.aot.gridworld.myAgents

import de.dailab.jiacvi.Agent
import de.dailab.jiacvi.AgentRef
import de.dailab.jiacvi.BrokerAgentRef
import de.dailab.jiacvi.aot.gridworld.*
import de.dailab.jiacvi.behaviour.act
import java.util.*
import kotlin.math.abs
import kotlin.math.max

/**
 * Helper function to calculate the Chebyshev distance between two points.
 */
private fun Position.distanceTo(other: Position): Int {
    return max(abs(this.x - other.x), abs(this.y - other.y))
}

/**
 * Helper extension property to determine if a WorkerAction is a movement action.
 */
private val WorkerAction.isDirectional: Boolean
    get() = this in listOf(
        WorkerAction.NORTH, WorkerAction.SOUTH, WorkerAction.EAST, WorkerAction.WEST,
        WorkerAction.NORTHEAST, WorkerAction.NORTHWEST, WorkerAction.SOUTHEAST, WorkerAction.SOUTHWEST
    )

class CollectAgent(private val collectID: String) : Agent(overrideName = collectID) {

    private val msgBroker by resolve<BrokerAgentRef>()
    private var setup: GameSetupInfo? = null
    private var currentPosition: Position? = null
    private var hasMaterial = false
    private var busy = false
    private var currentPath: Queue<WorkerAction> = LinkedList()

    // --- Agent State ---
    private var cnpConversationId: String? = null
    private val proposals = mutableMapOf<String, Propose>()
    private var isWaitingForBids = false
    private var cnpTimeoutTurn = 0
    private var cnpRetryCooldown = 0
    private var awaitingHandoffForConversationId: String? = null

    // --- State for Frontier-Based Exploration ---
    private val visitedCells = mutableSetOf<Position>()
    private val knownMaterialSources = mutableSetOf<Position>()
    private val recentlyFailedSources = mutableMapOf<Position, Int>()
    private val FAILED_SOURCE_COOLDOWN = 15

    override fun behaviour() = act {
        on<GameSetupInfo> {
            setup = it
            log.info("CollectAgent ($collectID): Game setup received. Starting exploration.")
            msgBroker subscribe self to BROADCAST_TOPIC
        }

        on<CurrentPosition> { message ->
            if (setup == null) return@on
            val currentPos = message.position
            currentPosition = currentPos
            val gameTurn = message.gameTurn

            // Update internal knowledge
            visitedCells.add(currentPos)
            message.vision.forEach { knownMaterialSources.add(it) }
            recentlyFailedSources.keys.forEach { knownMaterialSources.remove(it) }
            recentlyFailedSources.entries.removeIf { (_, turn) -> gameTurn > turn + FAILED_SOURCE_COOLDOWN }

            if (busy) return@on
            busy = true

            if (isWaitingForBids && gameTurn >= cnpTimeoutTurn) {
                evaluateProposals()
            }

            if (currentPath.isNotEmpty()) {
                sendAction(currentPath.poll())
                return@on
            }

            if (hasMaterial) {
                // Logic for when holding material
                if (awaitingHandoffForConversationId != null) {
                    log.info("CollectAgent ($collectID): Holding material, awaiting handoff.")
                    busy = false
                } else if (!isWaitingForBids) {
                    if (cnpRetryCooldown <= 0) {
                        startCNP(gameTurn)
                    } else {
                        cnpRetryCooldown--
                        busy = false
                    }
                } else {
                    busy = false
                }
            } else { // No material: Use exploration logic
                if (knownMaterialSources.contains(currentPos)) {
                    requestTakeMaterial(gameTurn, currentPos)
                    return@on
                }

                // ## FIX ## Manually find the closest material to avoid type errors.
                var closestMaterial: Position? = null
                var minMaterialDist = Int.MAX_VALUE
                for (material in knownMaterialSources) {
                    val dist = currentPos.distanceTo(material)
                    if (dist < minMaterialDist) {
                        minMaterialDist = dist
                        closestMaterial = material
                    }
                }

                if (closestMaterial != null) {
                    log.info("CollectAgent ($collectID): Moving to closest known material at $closestMaterial.")
                    findAndFollowPath(closestMaterial)
                    return@on
                }

                val frontierCell = findClosestFrontierCell(currentPos)
                if (frontierCell != null) {
                    log.info("CollectAgent ($collectID): No known materials. Exploring nearest frontier cell: $frontierCell")
                    findAndFollowPath(frontierCell)
                    return@on
                }

                log.warn("CollectAgent ($collectID): No known materials or frontier. Exploring randomly.")
                exploreRandomly()
            }
        }

        on<Propose> { proposal ->
            if (isWaitingForBids && proposal.conversationId == cnpConversationId) {
                proposals[proposal.repairerId] = proposal
            }
        }
    }

    private fun findClosestFrontierCell(currentPos: Position): Position? {
        val obstacles = setup?.obstacles ?: return null
        val size = setup?.size ?: return null
        val frontier = mutableSetOf<Position>()

        for (cell in visitedCells) {
            for (action in WorkerAction.values().filter { it.isDirectional }) {
                val neighbor: Position? = cell.applyMove(action, size)
                if (neighbor != cell && neighbor !in visitedCells && neighbor !in obstacles) {
                    if (neighbor != null) {
                        frontier.add(neighbor)
                    }
                }
            }
        }

        // ## FIX ## Manually find the closest frontier cell to avoid type errors.
        if (frontier.isEmpty()) {
            return null
        }
        var bestTarget: Position? = null
        var minDistance = Int.MAX_VALUE
        for (cell in frontier) {
            val distance = currentPos.distanceTo(cell)
            if (distance < minDistance) {
                minDistance = distance
                bestTarget = cell
            }
        }
        return bestTarget
    }

    private fun findAndFollowPath(target: Position) {
        val currentPos = currentPosition ?: return
        Pathfinder.findPath(currentPos, target, setup!!.obstacles, setup!!.size)?.let {
            currentPath = it
            if (currentPath.isNotEmpty()) {
                sendAction(currentPath.poll())
            } else {
                busy = false
            }
        } ?: exploreRandomly()
    }

    private fun sendAction(action: WorkerAction) {
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(WorkerActionRequest(collectID, action)) { response ->
            if (response == null || (!response.state && response.flag != ActionFlag.MAX_ACTIONS)) {
                currentPath.clear()
            }
            busy = false
        } ?: run { busy = false }
    }

    private fun exploreRandomly() {
        val currentPos = currentPosition ?: return
        val directionalActions = WorkerAction.values().filter { it.isDirectional }.shuffled()
        for (action in directionalActions) {
            val potentialNextPos = currentPos.applyMove(action, setup!!.size)
            if (potentialNextPos != currentPos && potentialNextPos !in setup!!.obstacles) {
                sendAction(action)
                return
            }
        }
        busy = false
    }

    private fun requestTakeMaterial(gameTurn: Int, materialPosition: Position) {
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(WorkerActionRequest(collectID, WorkerAction.TAKE)) { response ->
            if (response.state) {
                hasMaterial = true
                currentPath.clear()
                knownMaterialSources.remove(materialPosition)
                startCNP(gameTurn)
            } else {
                recentlyFailedSources[materialPosition] = gameTurn
                knownMaterialSources.remove(materialPosition)
                hasMaterial = false
                busy = false
            }
        } ?: run { busy = false }
    }

    private fun startCNP(gameTurn: Int) {
        val materialPos = currentPosition ?: return
        cnpConversationId = "cnp-${UUID.randomUUID()}"
        proposals.clear()
        isWaitingForBids = true
        cnpTimeoutTurn = gameTurn + 5
        cnpRetryCooldown = 0
        msgBroker.publish(BROADCAST_TOPIC, CallForProposals(collectID, materialPos, cnpConversationId!!))
        busy = false
    }

    private fun evaluateProposals() {
        if (!isWaitingForBids) return
        val currentEvalConvId = cnpConversationId!!
        if (proposals.isEmpty()) {
            cnpRetryCooldown = 5
        } else {
            val winner = proposals.minByOrNull { it.value.bid }!!
            val meetingPos = currentPosition
            if (meetingPos != null) {
                system.resolve(winner.key) tell AcceptProposal(meetingPos, currentEvalConvId)
                awaitingHandoffForConversationId = currentEvalConvId
            }
            proposals.filter { it.key != winner.key }.forEach { (id, _) ->
                system.resolve(id) tell RejectProposal(currentEvalConvId)
            }
        }
        proposals.clear()
        isWaitingForBids = false
        cnpConversationId = null
    }
}