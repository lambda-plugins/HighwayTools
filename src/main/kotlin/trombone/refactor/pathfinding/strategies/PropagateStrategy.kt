package trombone.refactor.pathfinding.strategies

import baritone.api.process.PathingCommand
import baritone.api.process.PathingCommandType
import com.lambda.client.event.SafeClientEvent
import trombone.refactor.pathfinding.MovementStrategy

object PropagateStrategy : MovementStrategy {
    override fun SafeClientEvent.generatePathingCommand(): PathingCommand {
        return PathingCommand(null, PathingCommandType.REQUEST_PAUSE)
    }

    override fun onLostControl() {
        TODO("Not yet implemented")
    }
}