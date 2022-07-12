package trombone.test

import com.lambda.client.event.SafeClientEvent
import net.minecraft.util.math.BlockPos
import trombone.test.tasks.PlaceTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

object TaskProcessor {
    val tasks = ConcurrentHashMap<BlockPos, BuildTask>()
    val currentTask: BuildTask? = null

    fun SafeClientEvent.doTick() {
        /* update old tasks */
        tasks.values.forEach {
            with(it) {
                update()
            }
        }

        /* get task with the highest priority */
        val sortedTasks = ConcurrentSkipListSet(compareBy<BuildTask> { it.priority })
        sortedTasks.addAll(tasks.values)

        sortedTasks.pollFirst()?.let {
            with(it) {
                if (isValid()) execute()
            }
        }
    }

    /* allows tasks to convert into different types */
    inline fun <reified T: BuildTask> BuildTask.convertTo(
        isSupport: Boolean = false,
        isFiller: Boolean = false,
        isContainer: Boolean = false,
        isLiquid: Boolean = false
    ) {
        val newTask = T::class.java.getDeclaredConstructor().newInstance(blockPos, targetBlock)
        newTask.isSupport = isSupport
        newTask.isFiller = isFiller
        newTask.isContainer = isContainer

        if (newTask is PlaceTask) {
            newTask.isLiquid = isLiquid
        }
        tasks[blockPos] = newTask
    }

    fun addTask(buildTask: BuildTask) {
        tasks[buildTask.blockPos] = buildTask
    }
}
