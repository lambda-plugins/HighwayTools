package trombone.refactor.pathfinding

import HighwayTools.movementStrategy
import baritone.api.process.PathingCommand
import baritone.api.process.PathingCommandType
import com.lambda.client.event.SafeClientEvent
import net.minecraft.util.math.BlockPos
import trombone.refactor.pathfinding.strategies.BackAndForthStrategy
import trombone.refactor.pathfinding.strategies.DistanceStrategy
import trombone.refactor.pathfinding.strategies.PropagateStrategy
import trombone.refactor.pathfinding.strategies.StayStrategy

object Navigator {
    private var origin: BlockPos = BlockPos.ORIGIN
    var strategy: MovementStrategy = movementStrategy.getInstance()
    var currentPathingCommand = PathingCommand(null, PathingCommandType.REQUEST_PAUSE)

    enum class EnumMoveStrategy {
        PROPAGATE { override fun getInstance() = PropagateStrategy },
        STAY { override fun getInstance() = StayStrategy },
        DISTANCE { override fun getInstance() = DistanceStrategy },
        BACK_AND_FORTH { override fun getInstance() = BackAndForthStrategy };

        abstract fun getInstance(): MovementStrategy
    }

    inline fun <reified T: MovementStrategy> changeStrategy() {
        strategy = T::class.java.newInstance()
    }

    fun setStrategyToDefault() {
        strategy = movementStrategy.getInstance()
    }

    private fun SafeClientEvent.executeStrategy() {
        with(strategy) {
            currentPathingCommand = generatePathingCommand()
        }
    }

    fun onLostControl() {
        strategy.onLostControl()
    }

    fun processInfo(): String {
        return "Trombone: ${currentPathingCommand.commandType.name}@${currentPathingCommand.goal ?: origin}"
    }
}