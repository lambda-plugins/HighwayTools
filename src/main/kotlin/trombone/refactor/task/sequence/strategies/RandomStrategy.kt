package trombone.refactor.task.sequence.strategies

import net.minecraft.util.math.BlockPos
import trombone.refactor.task.BuildTask
import trombone.refactor.task.sequence.TaskSequenceStrategy
import java.util.concurrent.ConcurrentHashMap

object RandomStrategy : TaskSequenceStrategy {
    override fun getNextTask(tasks: ConcurrentHashMap<BlockPos, BuildTask>): BuildTask? {
        TODO("Not yet implemented")
    }
}