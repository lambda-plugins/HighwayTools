package trombone.handler

import HighwayTools.anonymizeStats
import HighwayTools.breakDelay
import HighwayTools.debugMessages
import HighwayTools.dynamicDelay
import HighwayTools.fakeSounds
import HighwayTools.fillerMat
import HighwayTools.food
import HighwayTools.ignoreBlocks
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.mode
import HighwayTools.multiBuilding
import HighwayTools.saveTools
import HighwayTools.saveFood
import HighwayTools.manageFood
import HighwayTools.storageManagement
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.util.items.*
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.world.*
import com.lambda.commons.extension.ceilToInt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import trombone.Blueprint.blueprint
import trombone.Blueprint.generateBluePrint
import trombone.Blueprint.isInsideBlueprintBuild
import trombone.IO.DebugMessages
import trombone.IO.disableError
import trombone.Pathfinder.MovementState
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.moveState
import trombone.Pathfinder.shouldBridge
import trombone.Pathfinder.startingBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Statistics.simpleMovingAverageBreaks
import trombone.Statistics.simpleMovingAveragePlaces
import trombone.Statistics.totalBlocksBroken
import trombone.Statistics.totalBlocksPlaced
import trombone.Trombone.Mode
import trombone.Trombone.module
import trombone.handler.Container.containerTask
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Container.getShulkerWith
import trombone.handler.Container.grindCycles
import trombone.handler.Container.handleRestock
import trombone.handler.Container.shulkerOpenTimer
import trombone.handler.Liquid.handleLiquid
import trombone.handler.Liquid.updateLiquidTask
import trombone.handler.Player.getEjectSlot
import trombone.handler.Player.lastHitVec
import trombone.handler.Player.moveToInventory
import trombone.handler.Player.swapOrMoveBestTool
import trombone.handler.Player.swapOrMoveBlock
import trombone.handler.Player.waitTicks
import trombone.interaction.Break.mineBlock
import trombone.interaction.Break.prePrimedPos
import trombone.interaction.Break.primedPos
import trombone.interaction.Place.extraPlaceDelay
import trombone.interaction.Place.placeBlock
import trombone.task.BlockTask
import trombone.task.TaskState
import java.util.*

object Tasks {
    val tasks = LinkedHashMap<BlockPos, BlockTask>()
    var sortedTasks: List<BlockTask> = emptyList()
    var lastTask: BlockTask? = null

    val stateUpdateMutex = Mutex()

    fun clearTasks() {
        tasks.clear()
        sortedTasks = emptyList()
        containerTask.updateState(TaskState.DONE)
        lastTask = null
        grindCycles = 0
    }

    fun SafeClientEvent.updateTasks() {
        val toRemove = LinkedList<BlockPos>()
        tasks.filter {
            it.value.taskState == TaskState.DONE
        }.forEach {
            if (currentBlockPos.distanceTo(it.key) > maxReach + 2) {
                if (it.value.toRemove) {
                    if (System.currentTimeMillis() - it.value.timestamp > 1000L) {
                        toRemove.add(it.key)
                    }
                } else {
                    it.value.toRemove = true
                    it.value.timestamp = System.currentTimeMillis()
                }
            }
        }
        toRemove.forEach {
            tasks.remove(it)
        }

        generateBluePrint()

        blueprint.forEach { (pos, block) ->
            addTask(pos, block)
        }
    }

    private fun SafeClientEvent.addTask(blockPos: BlockPos, targetBlock: Block) {
        val currentState = world.getBlockState(blockPos)
        when {
            /* Out of range, or is container pos and start padding */
            // ToDo: Fix padding for diagonal
            currentBlockPos.distanceTo(blockPos) > maxReach ||
                (blockPos == containerTask.blockPos && containerTask.taskState != TaskState.DONE) ||
                startingBlockPos.add(
                    startingDirection
                        .clockwise(4)
                        .directionVec
                        .multiply((maxReach * 2).ceilToInt() - 1)
                ).distanceTo(blockPos) < maxReach * 2 -> {
                //
            }
            /* Ignored blocks */
            ignoreBlocks.contains(currentState.block.registryName.toString()) -> {
                safeTask(blockPos, TaskState.DONE, currentState.block)
            }
            /* Is in desired state */
            currentState.block == targetBlock -> {
                safeTask(blockPos, TaskState.DONE, currentState.block)
            }
            /* To place */
            currentState.isReplaceable && targetBlock != Blocks.AIR -> {
                if (checkSupport(blockPos, targetBlock) ||
                    !world.checkNoEntityCollision(AxisAlignedBB(blockPos), null)) {
                    safeTask(blockPos, TaskState.DONE, targetBlock)
                } else {
                    safeTask(blockPos, TaskState.PLACE, targetBlock)
                }
            }
            /* Is liquid */
            currentState.block is BlockLiquid -> {
                safeTask(blockPos, TaskState.LIQUID, Blocks.AIR).updateLiquid(this)
            }
            /* Break to place */
            else -> {
                if (checkSupport(blockPos, targetBlock)) {
                    safeTask(blockPos, TaskState.DONE, currentState.block)
                } else {
                    safeTask(blockPos, TaskState.BREAK, targetBlock)
                }
            }
        }
    }

    fun SafeClientEvent.runTasks() {
        when {
            containerTask.taskState != TaskState.DONE -> {
                val eyePos = player.getPositionEyes(1.0f)
                containerTask.updateTask(this, eyePos)
                if (containerTask.stuckTicks > containerTask.taskState.stuckTimeout) {
                    if (containerTask.taskState == TaskState.PICKUP) {
                        MessageSendHelper.sendChatMessage("${module.chatName} Can't pickup ${containerTask.item.registryName}@(${containerTask.blockPos.asString()})")
                        moveState = MovementState.RUNNING
                    } else {
                        MessageSendHelper.sendChatMessage("${module.chatName} Failed container action: ${containerTask.item.registryName}@(${containerTask.blockPos.asString()})")
                    }
                    containerTask.updateState(TaskState.DONE)
                    return
                }
                tasks.values.forEach {
                    doTask(it, true)
                }
                doTask(containerTask, false)
            }
            grindCycles > 0 -> {
                if (storageManagement) {
                    if (player.inventorySlots.countItem(Items.DIAMOND_PICKAXE) > saveTools) {
                        handleRestock(material.item)
                    } else {
                        handleRestock(Items.DIAMOND_PICKAXE)
                    }
                }
            }
            tasks.values.all { it.taskState == TaskState.DONE } -> {
                if (storageManagement && manageFood && player.inventorySlots.countItem(food) < saveFood) {
                    handleRestock(food)
                }

                updateTasks()
            }
            else -> {
                waitTicks--

                if (storageManagement && manageFood && player.inventorySlots.countItem(food) < saveFood) {
                    handleRestock(food)
                }

                tasks.values.toList().forEach {
                    doTask(it, true)
                }

                sortTasks()

                for (task in sortedTasks) {
                    if (!checkStuckTimeout(task)) return
                    if (task.taskState != TaskState.DONE && waitTicks > 0) return
                    if (task.taskState == TaskState.PLACE && task.sequence.isEmpty()) continue

                    doTask(task, false)
                    when (task.taskState) {
                        TaskState.DONE, TaskState.BROKEN, TaskState.PLACED -> {
                            continue
                        }
                        else -> {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.checkSupport(pos: BlockPos, block: Block): Boolean {
        return mode == Mode.HIGHWAY &&
            startingDirection.isDiagonal &&
            world.getBlockState(pos.up()).block == material &&
            block == fillerMat
    }

    fun SafeClientEvent.safeTask(blockPos: BlockPos, taskState: TaskState, material: Block): BlockTask {
        val task = BlockTask(blockPos, taskState, material)
        tasks[blockPos]?.let {
            if (it.stuckTicks > it.taskState.stuckTimeout ||
                taskState == TaskState.LIQUID ||
                (it.taskState != taskState &&
                    (it.taskState == TaskState.DONE ||
                        (it.taskState == TaskState.PLACE && !world.isPlaceable(it.blockPos))))) {
//                (it.taskState != taskState &&
//                    it.taskState != TaskState.BREAKING &&
//                    it.taskState != TaskState.PENDING_BREAK &&
//                    it.taskState != TaskState.PENDING_PLACE)) {
                tasks[blockPos] = task
            }
        } ?: run {
            tasks[blockPos] = task
        }
        return task
    }

    fun SafeClientEvent.isTaskDone(pos: BlockPos) =
        tasks[pos]?.let {
            it.taskState == TaskState.DONE && world.getBlockState(pos).block != Blocks.PORTAL
        } ?: false

    private fun SafeClientEvent.sortTasks() {
        val eyePos = player.getPositionEyes(1.0f)
        tasks.values.forEach {
            it.updateTask(this, eyePos)
        }

        if (multiBuilding) {
            tasks.values.forEach {
                it.shuffle()
            }

            runBlocking {
                stateUpdateMutex.withLock {
                    sortedTasks = tasks.values.sortedWith(
                        compareBy<BlockTask> {
                            it.taskState.ordinal
                        }.thenBy {
                            it.stuckTicks
                        }.thenBy {
                            it.shuffle
                        }
                    )
                }
            }
        } else {
            runBlocking {
                stateUpdateMutex.withLock {
                    sortedTasks = tasks.values.sortedWith(
                        compareBy<BlockTask> {
                            it.taskState.ordinal
                        }.thenBy {
                            it.stuckTicks
                        }.thenBy {
                            if (it.isLiquidSource) {
                                0
                            } else {
                                1
                            }
                        }.thenBy {
                            if (moveState == MovementState.BRIDGE) {
                                if (it.sequence.isEmpty()) {
                                    69
                                } else {
                                    it.sequence.size
                                }
                            } else {
                                it.startDistance
                            }
                        }.thenBy {
                            it.eyeDistance
                        }
                    )
                }
            }
        }
    }

    private fun SafeClientEvent.checkStuckTimeout(blockTask: BlockTask): Boolean {
        val timeout = blockTask.taskState.stuckTimeout

        if (blockTask.stuckTicks > timeout) {
            when (blockTask.taskState) {
                TaskState.PENDING_BREAK -> {
                    blockTask.updateState(TaskState.BREAK)
                }
                TaskState.PENDING_PLACE -> {
                    blockTask.updateState(TaskState.PLACE)
                }
                else -> {
                    if (debugMessages != DebugMessages.OFF) {
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

                    updateTasks()
                    return false
                }
            }
        }

        return true
    }

    private fun SafeClientEvent.doTask(blockTask: BlockTask, updateOnly: Boolean) {
        if (!updateOnly) blockTask.onTick()

        when (blockTask.taskState) {
            TaskState.RESTOCK -> {
                doRestock()
            }
            TaskState.PICKUP -> {
                doPickup()
            }
            TaskState.OPEN_CONTAINER -> {
                doOpenContainer()
            }
            TaskState.BREAKING -> {
                doBreaking(blockTask, updateOnly)
            }
            TaskState.BROKEN -> {
                doBroken(blockTask)
            }
            TaskState.PLACED -> {
                doPlaced(blockTask)
            }
            TaskState.BREAK -> {
                doBreak(blockTask, updateOnly)
            }
            TaskState.PLACE, TaskState.LIQUID -> {
                doPlace(blockTask, updateOnly)
            }
            TaskState.PENDING_BREAK, TaskState.PENDING_PLACE -> {
                blockTask.onStuck()
            }
            else -> { }
        }
    }

    private fun SafeClientEvent.doRestock() {
        if (mc.currentScreen is GuiContainer && containerTask.isLoaded) {
            val container = player.openContainer

            container.getSlots(0..26).firstItem(containerTask.item)?.let {
                moveToInventory(it)
            } ?: run {
                getShulkerWith(container.getSlots(0..26), containerTask.item)?.let {
                    moveToInventory(it)
                } ?: run {
                    disableError("No ${containerTask.item.registryName} left in any container.")
                }
            }
        } else {
            containerTask.updateState(TaskState.OPEN_CONTAINER)
        }
    }

    private fun SafeClientEvent.doPickup() {
        if (getCollectingPosition() == null) {
            moveState = MovementState.RUNNING
            containerTask.updateState(TaskState.DONE)
            if (grindCycles > 0) {
                grindCycles = (player.inventorySlots.count { it.stack.isEmpty ||
                    InventoryManager.ejectList.contains(it.stack.item.registryName.toString()) } - 1) * 8 -
                    (player.inventorySlots.countBlock(Blocks.OBSIDIAN) / 8)
            }
        } else {
            if (player.inventorySlots.firstEmpty() == null) {
                getEjectSlot()?.let {
                    throwAllInSlot(it)
                }
            } else {
                // ToDo: Resolve ghost slot
            }
            containerTask.onStuck()
        }
    }

    private fun SafeClientEvent.doOpenContainer() {
        if (containerTask.isOpen) {
            containerTask.updateState(TaskState.RESTOCK)
        } else {
            val center = containerTask.blockPos.toVec3dCenter()
            val diff = player.getPositionEyes(1f).subtract(center)
            val normalizedVec = diff.normalize()

            val side = EnumFacing.getFacingFromVector(normalizedVec.x.toFloat(), normalizedVec.y.toFloat(), normalizedVec.z.toFloat())
            val hitVecOffset = getHitVecOffset(side)

            lastHitVec = getHitVec(containerTask.blockPos, side)

            if (shulkerOpenTimer.tick(20)) {
                connection.sendPacket(CPacketPlayerTryUseItemOnBlock(containerTask.blockPos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat()))
                player.swingArm(EnumHand.MAIN_HAND)
            }
        }
    }

    private fun SafeClientEvent.doBreaking(blockTask: BlockTask, updateOnly: Boolean) {
        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                waitTicks = breakDelay
                blockTask.updateState(TaskState.BROKEN)
                return
            }
            is BlockLiquid -> {
                updateLiquidTask(blockTask)
                return
            }
        }

        if (!updateOnly && swapOrMoveBestTool(blockTask)) {
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doBroken(blockTask: BlockTask) {
        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                totalBlocksBroken++

                tasks.forEach { (_, task) ->
                    if (task.taskState == TaskState.BREAK) task.resetStuck()
                }

                // Instant break exploit
                if (blockTask.blockPos == prePrimedPos) {
                    primedPos = prePrimedPos
                    prePrimedPos = BlockPos.NULL_VECTOR
                }

                // Statistics
                simpleMovingAverageBreaks.add(System.currentTimeMillis())

                when {
                    blockTask.block == Blocks.AIR -> {
                        if (fakeSounds) {
                            val soundType = blockTask.block.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                            world.playSound(player, blockTask.blockPos, soundType.breakSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                        }
                        blockTask.updateState(TaskState.DONE)
                    }
                    blockTask == containerTask -> {
                        if (containerTask.collect) {
                            moveState = MovementState.PICKUP
                            blockTask.updateState(TaskState.PICKUP)
                        } else {
                            blockTask.updateState(TaskState.DONE)
                        }
                    }
                    else -> {
                        blockTask.updateState(TaskState.PLACE)
                    }
                }
            }
            else -> {
                blockTask.updateState(TaskState.BREAK)
            }
        }
    }

    private fun SafeClientEvent.doPlaced(blockTask: BlockTask) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        when {
            blockTask.block == currentBlock && currentBlock != Blocks.AIR -> {
                totalBlocksPlaced++
                prePrimedPos = blockTask.blockPos
                simpleMovingAveragePlaces.add(System.currentTimeMillis())

                if (dynamicDelay && extraPlaceDelay > 0) extraPlaceDelay -= 1

                if (blockTask == containerTask) {
                    if (blockTask.destroy) {
                        blockTask.updateState(TaskState.BREAK)
                    } else {
                        blockTask.updateState(TaskState.RESTOCK)
                    }
                } else {
                    blockTask.updateState(TaskState.DONE)
                }
                if (fakeSounds) {
                    val soundType = currentBlock.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                    world.playSound(player, blockTask.blockPos, soundType.placeSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                }
            }
            blockTask.block == currentBlock && currentBlock == Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            blockTask.block == Blocks.AIR && currentBlock != Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            else -> {
                blockTask.updateState(TaskState.PLACE)
            }
        }
    }

    private fun SafeClientEvent.doBreak(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (ignoreBlocks.contains(currentBlock.registryName.toString()) &&
            !blockTask.isShulker &&
            !isInsideBlueprintBuild(blockTask.blockPos) ||
            currentBlock == Blocks.PORTAL ||
            currentBlock == Blocks.END_PORTAL ||
            currentBlock == Blocks.END_PORTAL_FRAME ||
            currentBlock == Blocks.BEDROCK) {
            blockTask.updateState(TaskState.DONE)
        }

        when (blockTask.block) {
            fillerMat -> {
                if (world.getBlockState(blockTask.blockPos.up()).block == material ||
                    (!world.isPlaceable(blockTask.blockPos) &&
                        world.getCollisionBox(blockTask.blockPos) != null)) {
                    blockTask.updateState(TaskState.DONE)
                    return
                }
            }
            material -> {
                if (currentBlock == material) {
                    blockTask.updateState(TaskState.DONE)
                    return
                }
            }
        }

        when (currentBlock) {
            Blocks.AIR -> {
                if (blockTask.block == Blocks.AIR) {
                    blockTask.updateState(TaskState.BROKEN)
                    return
                } else {
                    blockTask.updateState(TaskState.PLACE)
                    return
                }
            }
            is BlockLiquid -> {
                updateLiquidTask(blockTask)
                return
            }
        }

        if (!updateOnly && player.onGround && swapOrMoveBestTool(blockTask)) {
            if (handleLiquid(blockTask)) return
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doPlace(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (shouldBridge() &&
            moveState != MovementState.RESTOCK &&
            player.positionVector.distanceTo(currentBlockPos) < 1) {
            moveState = MovementState.BRIDGE
        }

        if (blockTask.taskState == TaskState.LIQUID &&
            world.getBlockState(blockTask.blockPos).block !is BlockLiquid) {
            blockTask.updateState(TaskState.DONE)
            return
        }

        when (blockTask.block) {
            material -> {
                if (currentBlock == material) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                }
            }
            fillerMat -> {
                if (currentBlock == fillerMat) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                } else if (currentBlock != fillerMat &&
                    mode == Mode.HIGHWAY &&
                    world.getBlockState(blockTask.blockPos.up()).block == material) {
                    blockTask.updateState(TaskState.DONE)
                    return
                }
            }
            Blocks.AIR -> {
                if (world.getBlockState(blockTask.blockPos).block !is BlockLiquid) {
                    if (currentBlock != Blocks.AIR) {
                        blockTask.updateState(TaskState.BREAK)
                    } else {
                        blockTask.updateState(TaskState.BROKEN)
                    }
                    return
                }
            }
        }

        if (!updateOnly) {
            if (!world.isPlaceable(blockTask.blockPos)) {
                if (debugMessages == DebugMessages.ALL) {
                    if (!anonymizeStats) {
                        MessageSendHelper.sendChatMessage("${module.chatName} Invalid place position @(${blockTask.blockPos.asString()}) Removing task")
                    } else {
                        MessageSendHelper.sendChatMessage("${module.chatName} Invalid place position. Removing task")
                    }
                }

                if (blockTask == containerTask) {
                    containerTask.updateState(TaskState.BREAK)
                } else {
                    tasks.remove(blockTask.blockPos)
                }
                return
            }

            if (!swapOrMoveBlock(blockTask)) {
                blockTask.onStuck()
                return
            }

            placeBlock(blockTask)
        }
    }
}