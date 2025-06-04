package de.dailab.jiacvi.aot.gridworld.myAgents

import de.dailab.jiacvi.aot.gridworld.Position
import java.io.Serializable

// For Setup
data class GameSetupInfo(
    val size: Position,
    val obstacles: Set<Position>,
    val repairPoints: List<Position>,
    val collectorIDs: List<String>,
    val repairIDs: List<String>
) : Serializable

// --- Messages for Contract Net Protocol ---

data class CallForProposals(
    val collectorId: String,
    val materialPosition: Position,
    val conversationId: String
) : Serializable

/**
 * CORRECTED: Now includes the ID of the RepairAgent sending the proposal.
 * This makes the sender's identity explicit.
 */
data class Propose(
    val repairerId: String,
    val bid: Int,
    val conversationId: String
) : Serializable

data class AcceptProposal(val meetingPosition: Position, val conversationId: String) : Serializable

data class RejectProposal(val conversationId: String) : Serializable