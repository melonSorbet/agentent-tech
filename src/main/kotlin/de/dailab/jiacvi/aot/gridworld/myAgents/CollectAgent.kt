package de.dailab.jiacvi.aot.gridworld.myAgents

import de.dailab.jiacvi.Agent
import de.dailab.jiacvi.AgentRef
import de.dailab.jiacvi.BrokerAgentRef
import de.dailab.jiacvi.aot.gridworld.*
import de.dailab.jiacvi.behaviour.act
import java.util.*

class CollectAgent(private val collectID: String) : Agent(overrideName = collectID) {

    private val msgBroker by resolve<BrokerAgentRef>()
    private var setup: GameSetupInfo? = null
    private var currentPosition: Position? = null

    private var hasMaterial = false
    private var busy = false
    private var currentPath: Queue<WorkerAction> = LinkedList()

    private var cnpConversationId: String? = null
    private val proposals = mutableMapOf<String, Propose>()
    private var isWaitingForBids = false
    private var cnpTimeoutTurn = 0
    private var cnpRetryCooldown = 0

    // New state variable to track handoff commitment
    private var awaitingHandoffForConversationId: String? = null

    override fun behaviour() = act {
        on<GameSetupInfo> {

            setup = it
            log.info("CollectAgent ($collectID): Game setup received. Attempting to subscribe to BROADCAST_TOPIC.") // Added for consistency
            msgBroker subscribe self to BROADCAST_TOPIC
        }

        on<CurrentPosition> { message ->
            if (setup == null) return@on
            val currentPos = message.position
            currentPosition = currentPos
            val gameTurn = message.gameTurn

            if (busy) {
                return@on
            }
            busy = true

            // If material status changed (e.g., server processed transfer and updated our state),
            // reset handoff state. The server should make `hasMaterial` false for this agent.
            if (!hasMaterial && awaitingHandoffForConversationId != null) {
                log.info("CollectAgent ($collectID): Material is now gone (was for CNP $awaitingHandoffForConversationId). Resetting handoff state.")
                awaitingHandoffForConversationId = null
            }

            if (isWaitingForBids && gameTurn >= cnpTimeoutTurn) {
                evaluateProposals()
                // After evaluateProposals, awaitingHandoffForConversationId might be set if a winner was chosen.
            }

            if (currentPath.isNotEmpty()) {
                sendAction(currentPath.poll())
                return@on
            }

            if (hasMaterial) {
                if (awaitingHandoffForConversationId != null) {
                    // We have material AND are waiting for a specific handoff for it
                    log.info("CollectAgent ($collectID): Holding material at $currentPos, awaiting handoff for CNP $awaitingHandoffForConversationId.")
                    busy = false // Just wait for server to update our hasMaterial state (or for RA to collect)
                } else if (!isWaitingForBids) {
                    // Have material, not awaiting a specific handoff, and not in a bidding phase for a new CFP
                    if (cnpRetryCooldown <= 0) {
                        log.info("CollectAgent ($collectID): Holding material at $currentPos (no current handoff commitment). Starting new CNP.")
                        startCNP(gameTurn)
                    } else {
                        log.info("CollectAgent ($collectID): Holding material at $currentPos. CNP cooldown: $cnpRetryCooldown turns.")
                        cnpRetryCooldown--
                        busy = false
                    }
                } else {
                    // Holding material AND isWaitingForBids for an active CNP (before timeout)
                    log.info("CollectAgent ($collectID): Holding material at $currentPos. Waiting for bids for CNP $cnpConversationId.")
                    busy = false
                }
            } else { // No material
                // If we previously were awaiting a handoff but now have no material, clear the state.
                if (awaitingHandoffForConversationId != null) {
                    log.info("CollectAgent ($collectID): No longer has material, clearing handoff state for CNP $awaitingHandoffForConversationId.")
                    awaitingHandoffForConversationId = null
                }

                val materialInVision = message.vision.firstOrNull()
                if (materialInVision != null) {
                    if (materialInVision == currentPos) {
                        log.info("CollectAgent ($collectID): On material at $currentPos. Requesting TAKE.")
                        requestTakeMaterial(gameTurn)
                    } else {
                        log.info("CollectAgent ($collectID): Material in vision at $materialInVision. Moving towards it.")
                        Pathfinder.findPath(currentPos, materialInVision, setup!!.obstacles, setup!!.size)?.let {
                            currentPath = it
                            if (currentPath.isNotEmpty()) {
                                sendAction(currentPath.poll())
                            } else {
                                log.warn("CollectAgent ($collectID): Path to $materialInVision was empty. Exploring.")
                                exploreRandomly()
                            }
                        } ?: run {
                            log.warn("CollectAgent ($collectID): Could not find path to $materialInVision. Exploring.")
                            exploreRandomly()
                        }
                    }
                } else {
                    log.info("CollectAgent ($collectID): Nothing in vision. Exploring randomly from $currentPos.")
                    exploreRandomly()
                }
            }
        }

        on<Propose> { proposal ->
            if (isWaitingForBids && proposal.conversationId == cnpConversationId) {
                log.info("CollectAgent ($collectID): Received proposal from ${proposal.repairerId} with bid ${proposal.bid} for CNP $cnpConversationId")
                proposals[proposal.repairerId] = proposal
            } else {
                log.warn("CollectAgent ($collectID): Received proposal for inactive/wrong CNP. OurCNP: $cnpConversationId (expecting: $awaitingHandoffForConversationId), TheirCNP: ${proposal.conversationId}")
            }
        }
    }

    private fun sendAction(action: WorkerAction) {
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(
            WorkerActionRequest(collectID, action)
        ) { response ->
            if (response == null || (!response.state && response.flag != ActionFlag.MAX_ACTIONS)) {
                log.warn("CollectAgent ($collectID): Action $action failed or bad response. Clearing path. Flag: ${response?.flag}")
                currentPath.clear()
            }
            // If the action was TAKE and it failed, hasMaterial will still be false.
            // If the action was TAKE and successful, requestTakeMaterial handles hasMaterial and starts CNP.
            // If it was a move, check if we arrived.
            if (currentPath.isEmpty() && action != WorkerAction.TAKE && action != WorkerAction.DROP) { // Assuming DROP is not used by CA
                // Arrived at a material spot or an intermediate exploration point
                log.info("CollectAgent ($collectID): Arrived after moving with $action.")
                // The main logic in on<CurrentPosition> will re-evaluate.
            }
            busy = false
        } ?: run {
            log.error("CollectAgent ($collectID): Server not found for WorkerActionRequest.")
            busy = false
        }
    }

    private fun exploreRandomly() {
        val directionalActions = listOf(
            WorkerAction.NORTH, WorkerAction.SOUTH, WorkerAction.EAST, WorkerAction.WEST,
            WorkerAction.NORTHEAST, WorkerAction.NORTHWEST, WorkerAction.SOUTHEAST, WorkerAction.SOUTHWEST
        ).shuffled()

        for (action in directionalActions) {
            val potentialNextPos = currentPosition?.applyMove(action, setup!!.size)
            if (potentialNextPos != null && potentialNextPos != currentPosition && potentialNextPos !in setup!!.obstacles) {
                sendAction(action)
                return
            }
        }
        log.info("CollectAgent ($collectID): No random move possible from $currentPosition. Staying put (NOOP effectively).")
        busy = false // No action taken, so release busy state.
    }

    private fun requestTakeMaterial(gameTurn: Int) {
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(
            WorkerActionRequest(collectID, WorkerAction.TAKE)
        ) { response ->
            if (response.state) {
                hasMaterial = true
                log.info("CollectAgent ($collectID): TAKE successful at $currentPosition.")
                currentPath.clear()
                startCNP(gameTurn) // Sets busy = false in its own execution
            } else {
                log.warn("CollectAgent ($collectID): TAKE action failed at $currentPosition. Flag: ${response.flag}")
                // Material might have been taken by another agent simultaneously
                hasMaterial = false // Ensure it's false
                busy = false
            }
        } ?: run {
            log.error("CollectAgent ($collectID): Server not found for TAKE action.")
            busy = false
        }
    }

    private fun startCNP(gameTurn: Int) {
        // Ensure we only start a CNP if we are not already committed to a handoff for the current material
        if (awaitingHandoffForConversationId != null) {
            log.warn("CollectAgent ($collectID): Tried to start new CNP while still awaiting handoff for $awaitingHandoffForConversationId. Aborting new CNP.")
            busy = false
            return
        }

        val materialPos = currentPosition
        if (materialPos == null) {
            log.error("CollectAgent ($collectID): CRITICAL - currentPosition is null before publishing CFP. Aborting CFP.")
            busy = false
            return
        }

        cnpConversationId = "cnp-${UUID.randomUUID()}"
        proposals.clear()
        isWaitingForBids = true
        cnpTimeoutTurn = gameTurn + 5
        cnpRetryCooldown = 0
        log.info("CollectAgent ($collectID): Starting CNP ($cnpConversationId) from $materialPos. Waiting for bids until turn $cnpTimeoutTurn.")
        msgBroker.publish(BROADCAST_TOPIC, CallForProposals(collectID, materialPos, cnpConversationId!!))
        busy = false
    }

    private fun evaluateProposals() {
        if (!isWaitingForBids) {
            // This might happen if evaluateProposals is called again after already processing this CNP
            // or if a Propose message arrives late.
            log.warn("CollectAgent ($collectID): evaluateProposals called but not in isWaitingForBids state for CNP $cnpConversationId. Ignoring.")
            return
        }

        val currentEvalConvId = cnpConversationId // Save it as it might be cleared
        log.info("CollectAgent ($collectID): Timeout or processing for CNP $currentEvalConvId. Evaluating ${proposals.size} proposals.")

        if (proposals.isEmpty()) {
            log.warn("CollectAgent ($collectID): No proposals received for $currentEvalConvId. Will retry after cooldown.")
            cnpRetryCooldown = 3 + Random().nextInt(3)
        } else {
            val winner = proposals.minByOrNull { it.value.bid }!! // proposals map String (repairerId) to Propose
            log.info("CollectAgent ($collectID): Winner for CNP $currentEvalConvId is ${winner.key} with bid ${winner.value.bid}. Sending acceptance and rejections.")

            val meetingPos = currentPosition
            if (meetingPos == null) {
                log.error("CollectAgent ($collectID): Cannot send AcceptProposal for $currentEvalConvId, currentPosition is null!")
                // Handle this error state - perhaps reset and retry CNP later
                cnpRetryCooldown = 3 + Random().nextInt(3) // Set cooldown
            } else {
                system.resolve(winner.key) tell AcceptProposal(meetingPos, currentEvalConvId!!)
                // Mark that we are now awaiting handoff for this specific conversation
                awaitingHandoffForConversationId = currentEvalConvId
                log.info("CollectAgent ($collectID): Committed to handoff for CNP $awaitingHandoffForConversationId with ${winner.key}.")
            }

            proposals.filter { it.key != winner.key }.forEach { (id, _) -> // Propose object 'prop' not needed here
                system.resolve(id) tell RejectProposal(currentEvalConvId!!)
            }
        }

        proposals.clear()
        isWaitingForBids = false // Bidding phase is over for this CNP
        cnpConversationId = null  // This specific CNP instance is concluded from CA's perspective
        // The busy flag is managed by the on<CurrentPosition> handler
    }
}