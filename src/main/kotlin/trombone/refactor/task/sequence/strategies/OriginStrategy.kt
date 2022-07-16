package trombone.refactor.task.sequence.strategies

import trombone.refactor.task.BuildTask
import trombone.refactor.task.sequence.TaskSequenceStrategy

object OriginStrategy : TaskSequenceStrategy {
    override fun getNextTask(tasks: List<BuildTask>): BuildTask? {
        val sortedTasks = tasks.sortedWith(compareBy { it.priority })

        return sortedTasks.firstOrNull()
    }
}