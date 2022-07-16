package trombone.refactor.task.sequence

import trombone.refactor.task.BuildTask

interface TaskSequenceStrategy {
    fun getNextTask(tasks: List<BuildTask>): BuildTask?
}