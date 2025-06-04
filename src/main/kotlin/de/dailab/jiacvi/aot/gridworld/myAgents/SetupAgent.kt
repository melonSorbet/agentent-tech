package de.dailab.jiacvi.aot.gridworld.myAgents

import de.dailab.jiacvi.Agent
import de.dailab.jiacvi.AgentRef
import de.dailab.jiacvi.aot.gridworld.*
import de.dailab.jiacvi.behaviour.act
import java.io.Serializable

class SetupAgent(private val setupID: String) : Agent(overrideName = setupID) {

    // Define a private message class to be used as a trigger.
    private data class StartSignal(val info: String = "Time to start setup") : Serializable

    /**
     * preStart() is a lifecycle method that is called when the agent is being created
     * but before it starts processing messages. This is the perfect place to send the
     * initial trigger message to ourselves.
     */
    override fun preStart() {
        super.preStart()
        // Send a message to ourself. This message will be put in our own mailbox
        // and will be processed only when we are fully initialized and running.
        self tell StartSignal()
    }

    override fun behaviour() = act {
        // The main logic is now safely inside a message handler.
        on<StartSignal> {
            val server = system.resolve(SERVER_NAME) as? AgentRef<SetupGameMessage>
            log.info("Requesting game setup from server...")

            // This 'ask' call will now work because it's executed
            // as part of the normal message-processing loop.
            server?.ask<SetupGameMessage, SetupGameResponse>(
                SetupGameMessage(setupID, "/grids/example.grid")
            ) { response ->
                if (response == null) {
                    log.error("Server setup failed! No response.")
                    return@ask
                }

                log.info("Server setup successful: $response")

                // Spawn agents
                response.collectorIDs.forEach { system.spawnAgent(CollectAgent(it)) }
                response.repairIDs.forEach { system.spawnAgent(RepairAgent(it)) }
                log.info("Spawned ${response.collectorIDs.size} collectors and ${response.repairIDs.size} repairers.")

                // Create the single setup info message
                val setupInfo = GameSetupInfo(
                    size = response.size,
                    obstacles = response.obstacles?.toSet() ?: emptySet(),
                    repairPoints = response.repairPoints,
                    collectorIDs = response.collectorIDs,
                    repairIDs = response.repairIDs
                )

                // Send setup info to all agents using tell
                (response.collectorIDs + response.repairIDs).forEach { agentId ->
                    system.resolve(agentId) tell setupInfo
                }
                log.info("Sent setup info to all agents.")

                // Start the game
                system.resolve(SERVER_NAME) tell StartGame(setupID)
                log.info("Sent StartGame message. Setup complete.")
            }
        }

        // This handler for the end of the game is unaffected and remains correct.
        on<EndGameMessage> {
            log.info("GAME OVER: Received $it. Shutting down system.")
        }
    }
}