package trombone.task

import HighwayTools.anonymizeStats
import HighwayTools.debugLevel
import HighwayTools.dynamicDelay
import HighwayTools.food
import HighwayTools.ignoreBlocks
import HighwayTools.manageFood
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.multiBuilding
import HighwayTools.saveFood
import HighwayTools.saveTools
import HighwayTools.storageManagement
import HighwayTools.width
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerInventoryManager
import com.lambda.client.util.items.countItem
import com.lambda.client.util.items.inventorySlots
import com.lambda.client.util.items.item
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.world.isPlaceable
import com.lambda.client.util.world.isReplaceable
import net.minecraft.block.BlockLiquid
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.ItemFood
import net.minecraft.item.ItemPickaxe
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.blueprint.BlueprintGenerator.blueprint
import trombone.blueprint.BlueprintGenerator.generateBluePrint
import trombone.blueprint.BlueprintGenerator.isInsideBlueprintBuild
import trombone.IO.DebugLevel
import trombone.Pathfinder.MovementState
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.moveState
import trombone.Pathfinder.startingBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Trombone.module
import trombone.blueprint.BlueprintTask
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
        blueprint.forEach { (pos, blueprintTask) ->
            generateTask(pos, blueprintTask)
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

    private fun SafeClientEvent.generateTask(blockPos: BlockPos, blueprintTask: BlueprintTask) {
        val currentState = world.getBlockState(blockPos)
        val eyePos = player.getPositionEyes(1f)

        when {
            /* start padding */
            startPadding(blockPos) -> { /* Ignore task */ }

            /* out of reach */
            eyePos.distanceTo(blockPos.toVec3dCenter()) >= maxReach + 1 -> { /* Ignore task */ }

            /* do not override container task */
            containerTask.blockPos == blockPos -> { /* Ignore task */ }

            /* ignored blocks */
            shouldBeIgnored(blockPos, currentState) -> {
                val blockTask = BlockTask(blockPos, TaskState.DONE, currentState.block)
                addTask(blockTask, blueprintTask)
            }

            /* is in desired state */
            currentState.block == blueprintTask.targetBlock -> {
                val blockTask = BlockTask(blockPos, TaskState.DONE, currentState.block)
                addTask(blockTask, blueprintTask)
            }

            /* is liquid */
            currentState.block is BlockLiquid -> {
                val blockTask = BlockTask(blockPos, TaskState.LIQUID, blueprintTask.targetBlock)
                blockTask.updateTask(this)

                if (blockTask.sequence.isNotEmpty()) {
                    addTask(blockTask, blueprintTask)
                }
            }

            /* to place */
            currentState.isReplaceable && blueprintTask.targetBlock != Blocks.AIR -> {
                /* support not needed */
                if (blueprintTask.isSupport && world.getBlockState(blockPos.up()).block == material) {
                    val blockTask = BlockTask(blockPos, TaskState.DONE, currentState.block)
                    addTask(blockTask, blueprintTask)
                    return
                }

                /* is blocked by entity */
                if (!world.checkNoEntityCollision(AxisAlignedBB(blockPos), null)) {
                    val blockTask = BlockTask(blockPos, TaskState.DONE, currentState.block)
                    addTask(blockTask, blueprintTask)
                    return
                }

                val blockTask = BlockTask(blockPos, TaskState.PLACE, blueprintTask.targetBlock)
                blockTask.updateTask(this)

                if (blockTask.sequence.isNotEmpty()) {
                    addTask(blockTask, blueprintTask)
                } else {
                    blockTask.updateState(TaskState.IMPOSSIBLE_PLACE)
                    addTask(blockTask, blueprintTask)
                }
            }

            /* To break */
            else -> {
                /* Is already filled */
                if (blueprintTask.isFiller) {
                    val blockTask = BlockTask(blockPos, TaskState.DONE, currentState.block)
                    addTask(blockTask, blueprintTask)
                    return
                }

                val blockTask = BlockTask(blockPos, TaskState.BREAK, blueprintTask.targetBlock)
                blockTask.updateTask(this)

                if (blockTask.eyeDistance < maxReach) {
                    addTask(blockTask, blueprintTask)
                }
            }
        }
    }

    fun SafeClientEvent.runTasks() {
        when {
            /* Finish the container task first */
            containerTask.taskState != TaskState.DONE -> {
                containerTask.updateTask(this)
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

                    it.updateTask(this)
                    if (multiBuilding) it.shuffle()
                }

                sortedTasks.clear()
                sortedTasks.addAll(tasks.values)

                sortedTasks.forEach taskExecution@{ task ->
                    if (!checkStuckTimeout(task)) return
                    if (task.taskState != TaskState.DONE && waitTicks > 0) return

                    doTask(task)
                    when (task.taskState) {
                        TaskState.DONE, TaskState.BROKEN, TaskState.PLACED -> return@taskExecution
                        else -> return
                    }
                }
            }
        }
    }

    fun SafeClientEvent.addTask(blockTask: BlockTask, blueprintTask: BlueprintTask) {
        blockTask.updateTask(this)
        blockTask.isFiller = blueprintTask.isFiller
        blockTask.isSupport = blueprintTask.isSupport

        tasks[blockTask.blockPos]?.let {
            if (it.stuckTicks > it.taskState.stuckTimeout
                || blockTask.taskState == TaskState.LIQUID
                || (it.taskState != blockTask.taskState
                    && (it.taskState == TaskState.DONE
                    || it.taskState == TaskState.IMPOSSIBLE_PLACE
                    || (it.taskState == TaskState.PLACE
                    && !world.isPlaceable(it.blockPos, AxisAlignedBB(it.blockPos)
            ))))) {
                tasks[blockTask.blockPos] = blockTask
            }
        } ?: run {
            tasks[blockTask.blockPos] = blockTask
        }
    }

    private fun checkStuckTimeout(blockTask: BlockTask): Boolean {
        val timeout = blockTask.taskState.stuckTimeout

        if (blockTask.stuckTicks < timeout) return true

        if (blockTask.taskState == TaskState.DONE) return true

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
        return false
    }

    private fun startPadding(c: BlockPos) = isBehindPos(startingBlockPos.add(startingDirection.directionVec), c)

    fun isBehindPos(origin: BlockPos, check: BlockPos): Boolean {
        val a = origin.add(startingDirection.counterClockwise(2).directionVec.multiply(width))
        val b = origin.add(startingDirection.clockwise(2).directionVec.multiply(width))

        return ((b.x - a.x) * (check.z - a.z) - (b.z - a.z) * (check.x - a.x)) > 0
    }

    private fun shouldBeIgnored(blockPos: BlockPos, currentState: IBlockState) =
        ignoreBlocks.contains(currentState.block.registryName.toString())
            && !isInsideBlueprintBuild(blockPos)
            && currentBlockPos.add(startingDirection.directionVec) != blockPos

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