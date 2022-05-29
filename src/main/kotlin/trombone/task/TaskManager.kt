package trombone.task

import HighwayTools.anonymizeStats
import HighwayTools.debugLevel
import HighwayTools.dynamicDelay
import HighwayTools.fillerMat
import HighwayTools.food
import HighwayTools.ignoreBlocks
import HighwayTools.manageFood
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.mode
import HighwayTools.multiBuilding
import HighwayTools.saveFood
import HighwayTools.saveTools
import HighwayTools.storageManagement
import com.lambda.client.LambdaMod
import com.lambda.client.commons.extension.ceilToInt
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerInventoryManager
import com.lambda.client.util.items.countItem
import com.lambda.client.util.items.inventorySlots
import com.lambda.client.util.items.item
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.world.isPlaceable
import com.lambda.client.util.world.isReplaceable
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.ItemFood
import net.minecraft.item.ItemPickaxe
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import trombone.Blueprint.blueprint
import trombone.Blueprint.generateBluePrint
import trombone.IO.DebugLevel
import trombone.Pathfinder.MovementState
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.moveState
import trombone.Pathfinder.startingBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Trombone.Structure
import trombone.Trombone.module
import trombone.handler.Container.containerTask
import trombone.handler.Container.grindCycles
import trombone.handler.Container.handleRestock
import trombone.handler.Inventory.waitTicks
import trombone.interaction.Place.extraPlaceDelay
import trombone.task.TaskExecutor.doTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

object TaskManager {
    val tasks = ConcurrentHashMap<BlockPos, BlockTask>()
    val sortedTasks = ConcurrentSkipListSet(blockTaskComparator())
    var lastTask: BlockTask? = null

    fun SafeClientEvent.populateTasks() {
        generateBluePrint()

        /* Generate tasks based on the blueprint */
        blueprint.forEach { (pos, block) ->
            generateTask(pos, block)
        }

        /* Remove old tasks */
        tasks.filter {
            it.value.taskState == TaskState.DONE
                && currentBlockPos.distanceTo(it.key) > maxReach + 2
        }.forEach {
            if (it.value.toRemove) {
                if (System.currentTimeMillis() - it.value.timestamp > 1000L) tasks.remove(it.key)
            } else {
                it.value.toRemove = true
                it.value.timestamp = System.currentTimeMillis()
            }
        }
    }

    private fun SafeClientEvent.generateTask(blockPos: BlockPos, targetBlock: Block) {
        val currentState = world.getBlockState(blockPos)
        when {
            /* Out of range, or is container pos and start padding */
            isPositionNeeded(blockPos) -> { /* Ignore task */ }
            /* Do not override container task */
            containerTask.blockPos == blockPos -> { /* Ignore task */ }
            /* Ignored blocks */
            ignoreBlocks.contains(currentState.block.registryName.toString()) -> {
                addTask(blockPos, TaskState.DONE, currentState.block)
            }
            /* Is in desired state */
            currentState.block == targetBlock -> {
                addTask(blockPos, TaskState.DONE, currentState.block)
            }
            /* To place */
            currentState.isReplaceable && targetBlock != Blocks.AIR -> {
                if (checkSupport(blockPos, targetBlock)
                    || !world.checkNoEntityCollision(AxisAlignedBB(blockPos), null)) {
                    addTask(blockPos, TaskState.DONE, targetBlock)
                } else {
                    addTask(blockPos, TaskState.PLACE, targetBlock)
                }
            }
            /* Is liquid */
            currentState.block is BlockLiquid -> {
                addTask(blockPos, TaskState.LIQUID, targetBlock).updateLiquid(this)
            }
            /* Break */
            else -> {
                if (checkSupport(blockPos, targetBlock)) {
                    addTask(blockPos, TaskState.DONE, currentState.block)
                } else {
                    addTask(blockPos, TaskState.BREAK, targetBlock)
                }
            }
        }
    }

    fun SafeClientEvent.runTasks() {
        when {
            /* Wait for PIM to finish all inventory transactions */
            !PlayerInventoryManager.isDone() -> {}

            /* Finish the container task first */
            containerTask.taskState != TaskState.DONE -> {
                val eyePos = player.getPositionEyes(1f)
                containerTask.updateTask(this, eyePos)
                if (containerTask.stuckTicks > containerTask.taskState.stuckTimeout) {
                    if (containerTask.taskState == TaskState.PICKUP) moveState = MovementState.RUNNING

                    MessageSendHelper.sendWarningMessage("${module.chatName} Failed container action ${containerTask.taskState.name} with ${containerTask.item.registryName}@(${containerTask.blockPos.asString()}) stuck for ${containerTask.stuckTicks} ticks")
                    containerTask.updateState(TaskState.DONE)
                } else {
                    tasks.values.forEach {
                        doTask(it, updateOnly = true)
                    }
                    doTask(containerTask)
                }
            }

            /* Check tools */
            storageManagement
                && player.inventorySlots.countItem<ItemPickaxe>() <= saveTools -> {
                // TODO: ItemPickaxe support
                handleRestock(Items.DIAMOND_PICKAXE)
            }

            /* Fulfill basic needs */
            storageManagement
                && manageFood
                && player.inventorySlots.countItem<ItemFood>() <= saveFood -> {
                // TODO: ItemFood support
                handleRestock(food)
            }

            /* Restock obsidian if needed */
            storageManagement && grindCycles > 0 && material == Blocks.OBSIDIAN -> {
                handleRestock(material.item)
            }

            /* Actually run the tasks */
            else -> {
                waitTicks--

                /* Only update tasks to check for changed circumstances */
                tasks.values.forEach {
                    doTask(it, updateOnly = true)

                    it.updateTask(this, player.getPositionEyes(1.0f))
                    if (multiBuilding) it.shuffle()
                }

                sortedTasks.addAll(tasks.values)

                sortedTasks.forEach taskExecution@{ task ->
                    if (!checkStuckTimeout(task)) return
                    if (task.taskState != TaskState.DONE && waitTicks > 0) return
                    if (task.taskState == TaskState.PLACE && task.sequence.isEmpty()) return@taskExecution

                    doTask(task)
                    when (task.taskState) {
                        TaskState.DONE, TaskState.BROKEN, TaskState.PLACED -> return@taskExecution
                        else -> return
                    }
                }
            }
        }
    }

    fun SafeClientEvent.addTask(blockPos: BlockPos, taskState: TaskState, material: Block): BlockTask {
        val task = BlockTask(blockPos, taskState, material)
        tasks[blockPos]?.let {
            if (it.stuckTicks > it.taskState.stuckTimeout
                || taskState == TaskState.LIQUID
                || (it.taskState != taskState
                    && (it.taskState == TaskState.DONE
                    || (it.taskState == TaskState.PLACE
                    && !world.isPlaceable(it.blockPos))))) {
                tasks[blockPos] = task
            }
        } ?: run {
            tasks[blockPos] = task
        }
        return task
    }

    private fun SafeClientEvent.checkSupport(pos: BlockPos, block: Block) =
        mode == Structure.HIGHWAY
            && startingDirection.isDiagonal
            && world.getBlockState(pos.up()).block == material
            && block == fillerMat

    private fun SafeClientEvent.checkStuckTimeout(blockTask: BlockTask): Boolean {
        val timeout = blockTask.taskState.stuckTimeout

        if (blockTask.stuckTicks < timeout) return true

        if (blockTask.taskState == TaskState.PENDING_BREAK) {
            blockTask.updateState(TaskState.BREAK)
            return false
        }

        if (blockTask.taskState == TaskState.PENDING_PLACE) {
            blockTask.updateState(TaskState.PLACE)
            return false
        }

        if (debugLevel != DebugLevel.OFF) {
            if (!anonymizeStats) {
                MessageSendHelper.sendChatMessage("${module.chatName} Stuck while ${blockTask.taskState}@(${blockTask.blockPos.asString()}) for more than $timeout ticks (${blockTask.stuckTicks}), refreshing data.")
            } else {
                MessageSendHelper.sendChatMessage("${module.chatName} Stuck while ${blockTask.taskState} for more than $timeout ticks (${blockTask.stuckTicks}), refreshing data.")
            }
        }

        when (blockTask.taskState) {
            TaskState.PLACE -> {
                if (dynamicDelay && extraPlaceDelay < 10 && moveState != MovementState.BRIDGE) extraPlaceDelay += 1
            }
            TaskState.PICKUP -> {
                MessageSendHelper.sendChatMessage("${module.chatName} Can't pickup ${containerTask.item.registryName}@(${containerTask.blockPos.asString()})")
                blockTask.updateState(TaskState.DONE)
                moveState = MovementState.RUNNING
            }
            else -> {
                blockTask.updateState(TaskState.DONE)
            }
        }

        populateTasks()
        return false
    }

    fun SafeClientEvent.isTaskDone(pos: BlockPos) =
        tasks[pos]?.let {
            it.taskState == TaskState.DONE
                && world.getBlockState(pos).block != Blocks.PORTAL
        } ?: false

    // ToDo: Fix padding for diagonal
    private fun isPositionNeeded(blockPos: BlockPos) =
        currentBlockPos.distanceTo(blockPos) > maxReach
            || (blockPos == containerTask.blockPos && containerTask.taskState != TaskState.DONE)
            || startingBlockPos.add(
            startingDirection
                .clockwise(4)
                .directionVec
                .multiply((maxReach * 2).ceilToInt() - 1)
        ).distanceTo(blockPos) < maxReach * 2

    fun clearTasks() {
        tasks.clear()
        sortedTasks.clear()
        containerTask.updateState(TaskState.DONE)
        lastTask = null
        grindCycles = 0
    }

    private fun blockTaskComparator() = compareBy<BlockTask> {
        it.taskState.ordinal
    }.thenBy {
        it.stuckTicks
    }.thenBy {
        if (it.isLiquidSource) 0 else 1
    }.thenBy {
        if (moveState == MovementState.BRIDGE) {
            if (it.sequence.isEmpty()) 69 else it.sequence.size
        } else {
            if (multiBuilding) it.shuffle else it.startDistance
        }
    }.thenBy {
        it.eyeDistance
    }
}