package trombone.refactor.pathfinding.strategies

import baritone.api.process.PathingCommand
import baritone.api.process.PathingCommandType
import com.lambda.client.event.SafeClientEvent
import net.minecraft.util.math.BlockPos
import trombone.refactor.pathfinding.MovementStrategy

object StayStrategy : MovementStrategy {
    override fun SafeClientEvent.generatePathingCommand(): PathingCommand {
        return PathingCommand(null, PathingCommandType.REQUEST_PAUSE)
    }

    override fun onLostControl() {
        TODO("Not yet implemented")
    }
}