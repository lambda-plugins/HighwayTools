package trombone.test.task

import HighwayTools.interactionLimit
import com.lambda.client.event.SafeClientEvent
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.test.task.tasks.PlaceTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentSkipListSet

object TaskProcessor {
    val tasks = ConcurrentHashMap<BlockPos, BuildTask>()
    private var currentTask: BuildTask? = null
    var waitTicks = 0
    var waitPenalty = 0

    val packetLimiter = ConcurrentLinkedDeque<Long>()

    fun SafeClientEvent.doTick() {
        /* update old tasks */
        tasks.values.forEach {
            with(it) {
                runUpdate()
            }
        }

        /* wait given delay */
        if (waitTicks > 0) {
            waitTicks--
            return
        }

        /* get task with the highest priority */
        val sortedTasks = ConcurrentSkipListSet(compareBy<BuildTask> { it.priority })
        sortedTasks.addAll(tasks.values)

        currentTask = sortedTasks.firstOrNull()

        currentTask?.let {
            with(it) {
                if (isValid()
                    && !runUpdate()
                ) {
                    runExecute()
                }
            }
        }
    }

    /* allows tasks to convert into different types */
    inline fun <reified T: BuildTask> BuildTask.convertTo(
        isSupportTask: Boolean = this.isSupportTask,
        isFillerTask: Boolean = this.isFillerTask,
        isContainerTask: Boolean = this.isContainerTask,
        isLiquidTask: Boolean = false
    ) {
        val newTask = T::class.java.getDeclaredConstructor().newInstance(blockPos, targetBlock)
        newTask.isSupportTask = isSupportTask
        newTask.isFillerTask = isFillerTask
        newTask.isContainerTask = isContainerTask

        if (newTask is PlaceTask) {
            newTask.isLiquidTask = isLiquidTask
        }
        tasks[blockPos] = newTask
    }

    fun addTask(buildTask: BuildTask) {
        tasks[buildTask.blockPos] = buildTask
    }

    fun getContainerTask() = tasks.values.firstOrNull { it.isContainerTask }

    val interactionLimitNotReached = packetLimiter.size < interactionLimit
}
