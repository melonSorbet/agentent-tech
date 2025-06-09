package de.dailab.jiacvi.aot.gridworld.myAgents

import de.dailab.jiacvi.Agent
import de.dailab.jiacvi.AgentRef
import de.dailab.jiacvi.BrokerAgentRef
import de.dailab.jiacvi.aot.gridworld.*
import de.dailab.jiacvi.behaviour.act
import java.util.*

class RepairAgent(private val repairID: String) : Agent(overrideName = repairID) {

    private val msgBroker by resolve<BrokerAgentRef>()
    private var setup: GameSetupInfo? = null
    private var currentPosition: Position? = null
    private var hasMaterial = false
    private var busy = false
    private var currentPath: Queue<WorkerAction> = LinkedList()
    private var activeConversationId: String? = null
    private var cnpPartnerId: String? = null
    private var isSubscribed = false

    // State flags for delayed server requests
    private var meetingPositionForTransfer: Position? = null
    private var arrivedForTransferWaitingNextTurn = false
    private var arrivedAtRepairPointWaitingNextTurn = false

    // A mutable list of repair points the agent believes are still open.
    private var knownOpenRepairPoints: MutableList<Position> = mutableListOf()

    override fun behaviour() = act {
        on<GameSetupInfo> { gameSetupInfo ->
            setup = gameSetupInfo
            // Create a mutable copy of the repair points from setup.
            knownOpenRepairPoints = gameSetupInfo.repairPoints.toMutableList()
            log.info("RepairAgent ($repairID): Game setup received. Storing ${knownOpenRepairPoints.size} repair points.")
        }

        on<CurrentPosition> { message ->
            val gameTurn = message.gameTurn
            currentPosition = message.position

            if (setup == null) { return@on }

            if (!isSubscribed) {
                log.info("RepairAgent ($repairID): Subscribing to $BROADCAST_TOPIC on turn $gameTurn.")
                msgBroker subscribe self to BROADCAST_TOPIC
                isSubscribed = true
            }
            if (busy) {
                return@on
            }
            busy = true

            // PRIORITY 1: Request material transfer if arrived last turn
            if (arrivedForTransferWaitingNextTurn) {
                requestTransferFromServer(gameTurn)
                return@on
            }

            // PRIORITY 2: Request DROP if arrived at repair point last turn
            if (arrivedAtRepairPointWaitingNextTurn) {
                requestDropMaterial(gameTurn)
                return@on
            }

            // PRIORITY 3: Continue moving
            if (currentPath.isNotEmpty()) {
                moveAlongPath(gameTurn)
            }
            // PRIORITY 4: Plan path to repair point if holding material
            else if (hasMaterial) {
                goToRepairPoint(gameTurn)
            }
            // PRIORITY 5: Idle
            else {
                busy = false
            }
        }

        listen<CallForProposals>(BROADCAST_TOPIC) { cfp ->
            val currentSetup = setup
            val currentPos = currentPosition
            if (currentSetup == null || currentPos == null) { return@listen }

            if (activeConversationId == null && !hasMaterial) {
                Pathfinder.findPath(currentPos, cfp.materialPosition, currentSetup.obstacles, currentSetup.size)?.let { path ->
                    val bid = path.size
                    activeConversationId = cfp.conversationId
                    cnpPartnerId = cfp.collectorId
                    system.resolve(cnpPartnerId!!) tell Propose(repairID, bid, activeConversationId!!)
                    log.info("RepairAgent ($repairID): Sent proposal for CNP ${cfp.conversationId} with bid $bid.")
                } ?: log.warn("RepairAgent ($repairID): No path found for CNP ${cfp.conversationId}, not bidding.")
            }
        }

        // ***** NEW HANDLER *****
        // Listen for broadcasts from other RepairAgents about completed repairs
        listen<RepairPointCompleted>(BROADCAST_TOPIC) { completedMsg ->
            if (knownOpenRepairPoints.contains(completedMsg.position)) {
                log.info("RepairAgent ($repairID): Received broadcast that ${completedMsg.position} has been repaired. Removing from my list.")
                knownOpenRepairPoints.remove(completedMsg.position)

                // Optional: If our current path was leading to this now-repaired point, we must change course.
                val lastPointInPath = currentPath.lastOrNull()?.let { currentPosition?.applyMove(it, setup!!.size) }
                if (currentPath.isNotEmpty() && lastPointInPath == completedMsg.position) {
                    log.warn("RepairAgent ($repairID): My current path was to the now-repaired point ${completedMsg.position}. Stopping and replanning.")
                    currentPath.clear()
                    // The main on<CurrentPosition> loop will call goToRepairPoint() again next turn to find a new destination.
                }
            }
        }

        on<AcceptProposal> { acceptance ->
            if (acceptance.conversationId == activeConversationId) {
                log.info("RepairAgent ($repairID): Proposal for CNP ${acceptance.conversationId} ACCEPTED! Moving to meet at ${acceptance.meetingPosition}.")
                meetingPositionForTransfer = acceptance.meetingPosition
                val currentPos = currentPosition ?: return@on
                val gameSetup = setup ?: return@on

                if (currentPos == meetingPositionForTransfer) {
                    arrivedForTransferWaitingNextTurn = true
                    busy = false
                } else {
                    Pathfinder.findPath(currentPos, meetingPositionForTransfer!!, gameSetup.obstacles, gameSetup.size)?.let { currentPath = it }
                    busy = false
                }
            }
        }

        on<RejectProposal> { rejection ->
            if (rejection.conversationId == activeConversationId) {
                log.info("RepairAgent ($repairID): Proposal for CNP ${activeConversationId} REJECTED.")
                activeConversationId = null
                cnpPartnerId = null
                meetingPositionForTransfer = null
            }
        }

        on<TransferInform> { informMessage ->
            if (cnpPartnerId == informMessage.fromID && activeConversationId != null) {
                log.info("RepairAgent ($repairID): Material transfer complete from ${informMessage.fromID} for CNP $activeConversationId.")
                hasMaterial = true
                activeConversationId = null
                cnpPartnerId = null
                meetingPositionForTransfer = null
                arrivedForTransferWaitingNextTurn = false
            }
        }
    }

    private fun moveAlongPath(gameTurn: Int) {
        val action = currentPath.poll() ?: run { busy = false; return }
        log.info("RepairAgent ($repairID): Turn $gameTurn. Moving with $action. Path left: ${currentPath.size}.")
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(WorkerActionRequest(repairID, action)) { response ->
            if (response == null || !response.state) {

                if (response == null || !response.state) {
                    log.warn("RepairAgent ($repairID): Move action $action failed. Flag: ${response?.flag}. Clearing path and CNP state.")
                    currentPath.clear()
                    if (!hasMaterial) {
                        activeConversationId = null; cnpPartnerId = null; meetingPositionForTransfer = null
                    }
                }
            }

            if (currentPath.isEmpty()) {
                log.info("RepairAgent ($repairID): Path is now empty after move.")
                if (!hasMaterial) {
                    arrivedForTransferWaitingNextTurn = true
                } else {
                    arrivedAtRepairPointWaitingNextTurn = true
                }
            }
            busy = false
        }
    }

    private fun requestTransferFromServer(gameTurn: Int) {
        log.info("RepairAgent ($repairID): Turn $gameTurn. Requesting material transfer from $cnpPartnerId.")
        system.resolve(SERVER_NAME) tell TransferMaterial(cnpPartnerId!!, repairID)
        system.resolve(cnpPartnerId!!) tell HandoffComplete(activeConversationId)
        arrivedForTransferWaitingNextTurn = false
        busy = false
    }

    private fun requestDropMaterial(gameTurn: Int) {
        log.info("RepairAgent ($repairID): Turn $gameTurn. Requesting DROP at $currentPosition.")
        arrivedAtRepairPointWaitingNextTurn = false
        val positionOfDropAttempt = currentPosition
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(
            WorkerActionRequest(repairID, WorkerAction.DROP)
        ) { responseDrop ->
        if (positionOfDropAttempt == null) {
            log.error("RepairAgent ($repairID): Cannot DROP, currentPosition is null.")
            busy = false
            return@ask
        }

            if (responseDrop != null && responseDrop.state) {
                log.info("RepairAgent ($repairID): DROP successful at $positionOfDropAttempt.")
                hasMaterial = false
                knownOpenRepairPoints.remove(positionOfDropAttempt) // Remove from our own list

                // ***** NEW LOGIC: BROADCAST THE SUCCESS *****
                log.info("RepairAgent ($repairID): Broadcasting RepairPointCompleted at $positionOfDropAttempt.")
                msgBroker.publish(BROADCAST_TOPIC, RepairPointCompleted(positionOfDropAttempt))

            } else {
                log.warn("RepairAgent ($repairID): DROP failed at $positionOfDropAttempt. Flag: ${responseDrop?.flag}")
                if (responseDrop?.flag == ActionFlag.NO_REPAIRPOINT) {
                    log.error("RepairAgent ($repairID): Updating knowledge: $positionOfDropAttempt is not an open repair point. Removing from list.")
                    knownOpenRepairPoints.remove(positionOfDropAttempt)
                }
            }
            busy = false
        }
    }

    private fun goToRepairPoint(gameTurn: Int) {
        log.info("RepairAgent ($repairID): Turn $gameTurn. Finding path to a repair point from ${knownOpenRepairPoints.size} known points.")
        val currentPos = currentPosition ?: run { log.error("RepairAgent ($repairID): CurrentPosition is null."); busy = false; return }
        val gameSetup = setup ?: run { log.error("RepairAgent ($repairID): Setup is null."); busy = false; return }

        if (knownOpenRepairPoints.isEmpty()) {
            log.warn("RepairAgent ($repairID): No known open repair points left. Idling with material.")
            busy = false
            return
        }

        val closestRepairPoint = knownOpenRepairPoints.minByOrNull {
            Pathfinder.findPath(currentPos, it, gameSetup.obstacles, gameSetup.size)?.size ?: Int.MAX_VALUE
        }

        if (closestRepairPoint != null) {
            if (currentPos == closestRepairPoint) {
                log.info("RepairAgent ($repairID): Already at repair point $closestRepairPoint. Setting flag to DROP next turn.")
                arrivedAtRepairPointWaitingNextTurn = true
            } else {
                Pathfinder.findPath(currentPos, closestRepairPoint, gameSetup.obstacles, gameSetup.size)?.let {
                    currentPath = it
                    log.info("RepairAgent ($repairID): Path to repair point $closestRepairPoint calculated (${currentPath.size} steps).")
                } ?: log.warn("RepairAgent ($repairID): Could not find path to $closestRepairPoint.")
            }
        } else {
            log.warn("RepairAgent ($repairID): No reachable repair points found.")
        }
        busy = false
    }
}