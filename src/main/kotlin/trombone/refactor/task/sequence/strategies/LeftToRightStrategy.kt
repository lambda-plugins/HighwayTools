package trombone.refactor.task.sequence.strategies

import trombone.refactor.task.BuildTask
import trombone.refactor.task.sequence.TaskSequenceStrategy

object LeftToRightStrategy : TaskSequenceStrategy {
    override fun getNextTask(tasks: List<BuildTask>): BuildTask? {
        TODO("Not yet implemented")
    }
}