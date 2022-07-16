package trombone.refactor.pathfinding

import baritone.api.process.PathingCommand
import com.lambda.client.event.SafeClientEvent

interface MovementStrategy {
    fun SafeClientEvent.generatePathingCommand(): PathingCommand
    fun onLostControl()
}