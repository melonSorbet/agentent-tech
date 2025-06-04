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

    override fun behaviour() = act {
        on<GameSetupInfo> { gameSetupInfo ->
            setup = gameSetupInfo
            log.info("RepairAgent ($repairID): Game setup received.")
        }

        on<CurrentPosition> { message ->
            if (!isSubscribed && setup != null) {
                log.info("RepairAgent ($repairID): Subscribing to $BROADCAST_TOPIC on first turn.")
                msgBroker subscribe self to BROADCAST_TOPIC
                isSubscribed = true
            }

            if (setup == null || busy) {
                if (busy) log.debug("RepairAgent ($repairID): Busy, skipping turn.")
                return@on
            }
            busy = true
            currentPosition = message.position

            if (currentPath.isNotEmpty()) {
                moveAlongPath()
            } else if (hasMaterial) {
                goToRepairPoint()
            } else {
                busy = false
            }
        }

        listen<CallForProposals>(BROADCAST_TOPIC) { cfp ->
            log.error("!!!!!!!! REPAIR AGENT ($repairID) LISTENED to CallForProposals on $BROADCAST_TOPIC - CFP ID: ${cfp.conversationId}, From: ${cfp.collectorId}, MaterialAt: ${cfp.materialPosition} !!!!!!!!")

            if (setup == null) {
                log.error("RepairAgent ($repairID): Setup is NULL. Cannot process CFP for ${cfp.conversationId}.")
                return@listen
            }
            if (currentPosition == null) {
                log.error("RepairAgent ($repairID): CurrentPosition is NULL. Cannot process CFP for ${cfp.conversationId}.")
                return@listen
            }
            val currentPos = currentPosition!!

            log.info("RepairAgent ($repairID): Processing CFP ${cfp.conversationId} via LISTEN. Current state: activeCNP='${activeConversationId}', hasMaterial=$hasMaterial")

            if (activeConversationId == null && !hasMaterial) {
                log.info("RepairAgent ($repairID): Conditions met for CNP ${cfp.conversationId}. Calculating path from $currentPos to ${cfp.materialPosition}.")
                val path = Pathfinder.findPath(currentPos, cfp.materialPosition, setup!!.obstacles, setup!!.size)
                val bid = path?.size ?: Int.MAX_VALUE

                log.info("RepairAgent ($repairID): Path to material for CNP ${cfp.conversationId} cost: $bid (Path null: ${path == null})")

                if (bid != Int.MAX_VALUE) {
                    activeConversationId = cfp.conversationId
                    cnpPartnerId = cfp.collectorId
                    val proposal = Propose(repairID, bid, activeConversationId!!)
                    log.info("RepairAgent ($repairID): Sending Propose with bid $bid for CNP ${activeConversationId!!} to ${cnpPartnerId!!}.")
                    system.resolve(cnpPartnerId!!) tell proposal
                } else {
                    log.warn("RepairAgent ($repairID): Bid is Int.MAX_VALUE. Not sending proposal for CNP ${cfp.conversationId}.")
                }
            } else {
                log.warn("RepairAgent ($repairID): Conditions NOT met to consider bidding for CNP ${cfp.conversationId}. Current activeCNP='${activeConversationId}', hasMaterial=$hasMaterial")
            }
        }

        on<AcceptProposal> { acceptance ->
            if (acceptance.conversationId == activeConversationId) {
                log.info("RepairAgent ($repairID): Proposal for CNP ${acceptance.conversationId} ACCEPTED! Moving to meet ${cnpPartnerId ?: "UNKNOWN PARTNER"} at ${acceptance.meetingPosition}.")
                val currentPos = currentPosition ?: run {
                    log.error("RepairAgent ($repairID): CurrentPosition is null on AcceptProposal for ${acceptance.conversationId}. Aborting.")
                    activeConversationId = null; cnpPartnerId = null
                    busy = false
                    return@on
                }
                val gameSetup = setup ?: run {
                    log.error("RepairAgent ($repairID): Setup is null on AcceptProposal for ${acceptance.conversationId}. Aborting.")
                    activeConversationId = null; cnpPartnerId = null
                    busy = false
                    return@on
                }
                Pathfinder.findPath(currentPos, acceptance.meetingPosition, gameSetup.obstacles, gameSetup.size)?.let { path ->
                    currentPath = path
                    if (currentPath.isEmpty() && currentPos == acceptance.meetingPosition) {
                        log.info("RepairAgent ($repairID): Already at meeting point ${acceptance.meetingPosition} for CNP ${acceptance.conversationId}.")
                        handleArrival() // Will set busy=false
                    } else if (currentPath.isEmpty()) {
                        log.warn("RepairAgent ($repairID): Path to meeting point ${acceptance.meetingPosition} is empty but not at destination for CNP ${acceptance.conversationId}. Resetting.")
                        activeConversationId = null; cnpPartnerId = null; busy = false
                    }
                } ?: run {
                    log.warn("RepairAgent ($repairID): Could not find path to meeting point ${acceptance.meetingPosition} for CNP ${acceptance.conversationId}. Resetting.")
                    activeConversationId = null; cnpPartnerId = null
                    busy = false
                }
            } else {
                log.warn("RepairAgent ($repairID): Received AcceptProposal for wrong/old CNP: ${acceptance.conversationId} (expected ${activeConversationId})")
            }
        }

        on<RejectProposal> { rejection ->
            if (rejection.conversationId == activeConversationId) {
                log.info("RepairAgent ($repairID): Proposal for CNP ${activeConversationId} REJECTED. Back to idle.")
                activeConversationId = null
                cnpPartnerId = null
            } else {
                log.warn("RepairAgent ($repairID): Received RejectProposal for wrong/old CNP: ${rejection.conversationId} (expected ${activeConversationId})")
            }
        }

        // Assuming TransferInform has at least `fromID: String`.
        // The 'toID' is implicit (this agent is the recipient).
        on<TransferInform> { informMessage -> // Renamed 'it' to 'informMessage' for clarity
            println("STUPID FUCKIN LOSER")

                log.info("RepairAgent ($repairID): Material transfer complete from ${informMessage.fromID} (related to CNP $activeConversationId).")
                hasMaterial = true
                currentPath.clear() // Clear any path to meeting point

                // This CNP interaction (for getting material) is now fulfilled.
                activeConversationId = null
                cnpPartnerId = null

        }
    }

    private fun moveAlongPath() {
        if (currentPath.isEmpty()) {
            handleArrival()
            return
        }

        val action = currentPath.poll()
        log.info("RepairAgent ($repairID): Moving with action: $action for CNP $activeConversationId. Path items remaining: ${currentPath.size}")
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        server?.ask<WorkerActionRequest, WorkerActionResponse>(
            WorkerActionRequest(repairID, action)
        ) { response ->
            if (response == null || (!response.state && response.flag != ActionFlag.MAX_ACTIONS)) {
                log.warn("RepairAgent ($repairID): Move action $action failed for CNP $activeConversationId. Clearing path. Flag: ${response?.flag}")
                currentPath.clear()
                activeConversationId = null
                cnpPartnerId = null
            }

            if (currentPath.isEmpty()) {
                log.info("RepairAgent ($repairID): Path is now empty after move for CNP $activeConversationId, handling arrival.")
                handleArrival()
            } else {
                busy = false
            }
        } ?: run {
            log.error("RepairAgent ($repairID): Server not found for WorkerActionRequest during move for CNP $activeConversationId.")
            currentPath.clear()
            activeConversationId = null; cnpPartnerId = null
            busy = false
        }
    }

    private fun handleArrival() {
        log.info("RepairAgent ($repairID): handleArrival called. HasMaterial: $hasMaterial, ActiveCNP: $activeConversationId, Partner: $cnpPartnerId")
        val server = system.resolve(SERVER_NAME) as? AgentRef<WorkerActionRequest>
        if (server == null) {
            log.error("RepairAgent ($repairID): Server not found for handleArrival action (CNP $activeConversationId).")
            busy = false
            return
        }

        // The primary condition is now based on whether the agent has a partner for a material exchange.
// This is a more reliable indicator of being at a "meeting point" vs. a "repair point".
        if (cnpPartnerId != null && activeConversationId != null) {
            log.info("RepairAgent ($repairID): Arrived at meeting point for CNP $activeConversationId. Requesting material transfer from $cnpPartnerId.")

            // A sanity check to ensure we don't already have material.
            if (!hasMaterial) {
                println("Requesting material handoff from partner: $cnpPartnerId.")
                println("halllllll")

                system.resolve(SERVER_NAME) tell TransferMaterial(cnpPartnerId!!, repairID)
            } else {
                log.warn("RepairAgent ($repairID): Arrived at meeting point but already have material! Aborting transfer.")
            }
            busy = false
        } else {
            // If there's no transfer partner, we must be at the final repair destination.
            log.info("RepairAgent ($repairID): Arrived at a repair point.")

            // Here, we check if we have the material needed to perform the repair.
            if (hasMaterial) {
                log.info("RepairAgent ($repairID): Has material. Dropping material to start repair.")
                server.ask<WorkerActionRequest, WorkerActionResponse>(
                    WorkerActionRequest(repairID, WorkerAction.DROP)
                ) { responseDrop ->
                    if (responseDrop.state) {
                        log.info("RepairAgent ($repairID): DROP successful.")
                        hasMaterial = false
                    } else {
                        log.warn("RepairAgent ($repairID): DROP failed. Flag: ${responseDrop.flag}")
                    }
                    busy = false
                }
            } else {
                // Handles the error case where the agent arrives at the repair point without any material.
                log.warn("RepairAgent ($repairID): Arrived at repair point but has NO material! Cannot work.")
                busy = false
            }
        }
    }

    private fun goToRepairPoint() {
        log.info("RepairAgent ($repairID): goToRepairPoint called (has material).")
        val currentPos = currentPosition ?: run {
            log.error("RepairAgent ($repairID): CurrentPosition is null in goToRepairPoint.")
            busy = false; return
        }
        val gameSetup = setup ?: run {
            log.error("RepairAgent ($repairID): Setup is null in goToRepairPoint.")
            busy = false; return
        }

        // Agent relies on its initial setup.repairPoints.
        // Server validates the DROP action.
        val closestRepairPoint = gameSetup.repairPoints
            .filter { repairP -> repairP.x != -1 && repairP.y != -1 } // Basic validity check from setup
            .minByOrNull { p ->
                Pathfinder.findPath(currentPos, p, gameSetup.obstacles, gameSetup.size)?.size ?: Int.MAX_VALUE
            }

        if (closestRepairPoint != null) {
            log.info("RepairAgent ($repairID): Found closest (known) repair point at $closestRepairPoint. Calculating path.")
            Pathfinder.findPath(currentPos, closestRepairPoint, gameSetup.obstacles, gameSetup.size)?.let { path ->
                currentPath = path
                if (currentPath.isNotEmpty()) {
                    log.info("RepairAgent ($repairID): Path to repair point $closestRepairPoint calculated. Size: ${currentPath.size}")
                } else {
                    log.warn("RepairAgent ($repairID): Path to repair point $closestRepairPoint was empty, though it was selected.")
                }
            } ?: log.warn("RepairAgent ($repairID): Could not find path to repair point $closestRepairPoint even after selecting it.")
        } else {
            log.warn("RepairAgent ($repairID): No (known) active repair points found or reachable from setup info.")
        }

        if (currentPath.isNotEmpty()) {
            moveAlongPath()
        } else {
            log.warn("RepairAgent ($repairID): No path to any repair point. Remaining idle with material.")
            busy = false
        }
    }
}