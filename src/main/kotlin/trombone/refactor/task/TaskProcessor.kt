package trombone.refactor.task

import HighwayTools.interactionLimit
import HighwayTools.taskStrategy
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.util.math.RotationUtils.getRotationTo
import net.minecraft.util.math.BlockPos
import trombone.Trombone.module
import trombone.refactor.task.sequence.TaskSequenceStrategy
import trombone.refactor.task.sequence.strategies.LeftToRightStrategy
import trombone.refactor.task.sequence.strategies.OriginStrategy
import trombone.refactor.task.sequence.strategies.RandomStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentSkipListSet

object TaskProcessor {
    val tasks = ConcurrentHashMap<BlockPos, BuildTask>()
    private var currentTask: BuildTask? = null
    var waitTicks = 0
    var waitPenalty = 0
    var taskSequenceStrategy = taskStrategy.getInstance()

    val packetLimiter = ConcurrentLinkedDeque<Long>()

    enum class EnumTaskSequenceStrategy {
        ORIGIN { override fun getInstance() = OriginStrategy },
        RANDOM { override fun getInstance() = RandomStrategy },
        LEFT_TO_RIGHT { override fun getInstance() = LeftToRightStrategy };

        abstract fun getInstance(): TaskSequenceStrategy
    }

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

        /* get task with the highest priority based on selection strategy */
        currentTask = taskSequenceStrategy.getNextTask(tasks)

        currentTask?.let {
            with(it) {
                if (isValid() && !runUpdate()) {
                    runExecute()

                    module.sendPlayerPacket {
                        rotate(getRotationTo(it.hitVec3d))
                    }
                }
            }
        }
    }

    /* allows tasks to convert into different types */
    inline fun <reified T: BuildTask> BuildTask.convertTo(
        isSupportTask: Boolean = this.isSupportTask,
        isFillerTask: Boolean = this.isFillerTask,
        isContainerTask: Boolean = this.isContainerTask
    ) {
        val newTask = T::class.java.getDeclaredConstructor().newInstance(blockPos, targetBlock)
        newTask.isSupportTask = isSupportTask
        newTask.isFillerTask = isFillerTask
        newTask.isContainerTask = isContainerTask

        tasks[blockPos] = newTask
    }

    fun addTask(buildTask: BuildTask) {
        tasks[buildTask.blockPos] = buildTask
    }

    fun getContainerTasks() = tasks.values.filter { it.isContainerTask }

    val interactionLimitNotReached = packetLimiter.size < interactionLimit
}
