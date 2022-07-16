package trombone.refactor.task.sequence

import net.minecraft.util.math.BlockPos
import trombone.refactor.task.BuildTask
import java.util.concurrent.ConcurrentHashMap

interface TaskSequenceStrategy {
    fun getNextTask(tasks: ConcurrentHashMap<BlockPos, BuildTask>): BuildTask?
}