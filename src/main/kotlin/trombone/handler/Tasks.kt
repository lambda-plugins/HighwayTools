package trombone.handler

import HighwayTools.anonymizeStats
import HighwayTools.breakDelay
import HighwayTools.bridging
import HighwayTools.debugMessages
import HighwayTools.dynamicDelay
import HighwayTools.fakeSounds
import HighwayTools.fillerMat
import HighwayTools.ignoreBlocks
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.mode
import HighwayTools.multiBuilding
import HighwayTools.saveTools
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.util.items.*
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.onMainThreadSafe
import com.lambda.client.util.world.*
import com.lambda.commons.extension.ceilToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import net.minecraft.util.math.BlockPos
import trombone.Blueprint.blueprint
import trombone.Blueprint.generateBluePrint
import trombone.Blueprint.isInsideBlueprintBuild
import trombone.IO.DebugMessages
import trombone.IO.disableError
import trombone.Pathfinder
import trombone.Pathfinder.MovementState
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.moveState
import trombone.Pathfinder.shouldBridge
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
import trombone.handler.Player.rotateTimer
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
    val pendingTasks = LinkedHashMap<BlockPos, BlockTask>()
    val doneTasks = LinkedHashMap<BlockPos, BlockTask>()
    var sortedTasks: List<BlockTask> = emptyList()
    var lastTask: BlockTask? = null

    val stateUpdateMutex = Mutex()

    fun clearTasks() {
        pendingTasks.clear()
        doneTasks.clear()
        containerTask.updateState(TaskState.DONE)
        lastTask = null
        grindCycles = 0
    }

    fun SafeClientEvent.updateTasks(originPos: BlockPos = currentBlockPos) {
        val toRemove = LinkedList<BlockPos>()
        doneTasks.forEach { (pos, task) ->
            if (originPos.distanceTo(pos) > maxReach + 2) {
                if (task.toRemove) {
                    if (System.currentTimeMillis() - task.timestamp > 1000L) {
                        toRemove.add(pos)
                    }
                } else {
                    task.toRemove = true
                    task.timestamp = System.currentTimeMillis()
                }
            }
        }
        toRemove.forEach {
            doneTasks.remove(it)
        }

        lastTask = null

        generateBluePrint(originPos)

        blueprint.forEach { (pos, block) ->
            if (!(pos == containerTask.blockPos && containerTask.taskState == TaskState.DONE) ||
                Pathfinder.startingBlockPos.add(
                    Pathfinder.startingDirection
                        .clockwise(4)
                        .directionVec
                        .multiply(maxReach.ceilToInt())
                ).distanceTo(pos) < maxReach) {
                if (block == Blocks.AIR) {
                    addTaskClear(pos, originPos)
                } else {
                    addTaskBuild(pos, block, originPos)
                }
            }
        }
    }

    private fun SafeClientEvent.addTaskBuild(blockPos: BlockPos, block: Block, originPos: BlockPos) {
        val blockState = world.getBlockState(blockPos)

        when {
            blockState.block == block && originPos.distanceTo(blockPos) < maxReach -> {
                addTaskToDone(blockPos, block)
            }
            world.isPlaceable(blockPos) -> {
                if (originPos.distanceTo(blockPos) < maxReach) {
                    if (checkSupport(blockPos, block)) {
                        addTaskToDone(blockPos, block)
                    } else {
                        addTaskToPending(blockPos, TaskState.PLACE, block)
                    }
                }
            }
            else -> {
                if (originPos.distanceTo(blockPos) < maxReach) {
                    if (checkSupport(blockPos, block)) {
                        addTaskToDone(blockPos, block)
                    } else {
                        addTaskToPending(blockPos, TaskState.BREAK, block)
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.checkSupport(pos: BlockPos, block: Block): Boolean {
        return mode == Mode.HIGHWAY &&
            Pathfinder.startingDirection.isDiagonal &&
            world.getBlockState(pos.up()).block == material &&
            block == fillerMat
    }

    private fun SafeClientEvent.addTaskClear(pos: BlockPos, originPos: BlockPos) {
        when {
            originPos.distanceTo(pos) > maxReach -> {
                //
            }
            world.isAirBlock(pos) -> {
                addTaskToDone(pos, Blocks.AIR)
            }
            ignoreBlocks.contains(world.getBlockState(pos).block.registryName.toString()) -> {
                addTaskToDone(pos, world.getBlockState(pos).block)
            }
            else -> {
                addTaskToPending(pos, TaskState.BREAK, Blocks.AIR)
            }
        }
    }

    fun SafeClientEvent.runTasks() {
        when {
            containerTask.taskState != TaskState.DONE -> {
                val eyePos = player.getPositionEyes(1.0f)
                containerTask.updateTask(this, eyePos)
                checkStuckTimeout(containerTask)
                pendingTasks.values.toList().forEach {
                    doTask(it, true)
                }
                doTask(containerTask, false)
            }
            grindCycles > 0 -> {
                if (player.inventorySlots.countItem(Items.DIAMOND_PICKAXE) > saveTools) {
                    handleRestock(material.item)
                } else {
                    handleRestock(Items.DIAMOND_PICKAXE)
                }
            }
            pendingTasks.isEmpty() -> {
                updateTasks()
            }
            else -> {
                waitTicks--

                pendingTasks.values.toList().forEach {
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

    fun addTaskToPending(blockPos: BlockPos, taskState: TaskState, material: Block) {
        pendingTasks[blockPos]?.let {
            if (it.taskState != taskState ||
                it.stuckTicks > it.taskState.stuckTimeout) {
                pendingTasks[blockPos] = (BlockTask(blockPos, taskState, material))
            }
        } ?: run {
            pendingTasks[blockPos] = (BlockTask(blockPos, taskState, material))
        }
    }

    private fun addTaskToDone(blockPos: BlockPos, material: Block) {
        doneTasks[blockPos]?.let {
            if (it.taskState != TaskState.DONE) {
                doneTasks[blockPos] = (BlockTask(blockPos, TaskState.DONE, material))
            }
        } ?: run {
            doneTasks[blockPos] = (BlockTask(blockPos, TaskState.DONE, material))
        }
    }

    fun SafeClientEvent.isTaskDone(pos: BlockPos) =
        (pendingTasks[pos] ?: doneTasks[pos])?.let {
            it.taskState == TaskState.DONE && world.getBlockState(pos).block != Blocks.PORTAL
        } ?: false

    fun checkTasks(pos: BlockPos): Boolean {
        return pendingTasks.values.all {
            it.taskState == TaskState.DONE || pos.distanceTo(it.blockPos) < maxReach - 0.7
        }
    }

    private fun SafeClientEvent.sortTasks() {
        val eyePos = player.getPositionEyes(1.0f)
        pendingTasks.values.forEach {
            it.updateTask(this, eyePos)
        }

        if (multiBuilding) {
            pendingTasks.values.forEach {
                it.shuffle()
            }

            runBlocking {
                stateUpdateMutex.withLock {
                    sortedTasks = pendingTasks.values.sortedWith(
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
                    sortedTasks = pendingTasks.values.sortedWith(
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
            TaskState.DONE -> {
                doDone(blockTask)
            }
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
        }
    }

    private fun doDone(blockTask: BlockTask) {
        pendingTasks.remove(blockTask.blockPos)
        doneTasks[blockTask.blockPos] = blockTask
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
            rotateTimer.reset()

            if (shulkerOpenTimer.tick(50)) {
                defaultScope.launch {
                    delay(20L)
                    onMainThreadSafe {
                        connection.sendPacket(CPacketPlayerTryUseItemOnBlock(containerTask.blockPos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat()))
                        player.swingArm(EnumHand.MAIN_HAND)
                    }
                }
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
                if (blockTask.blockPos == prePrimedPos) {
                    primedPos = prePrimedPos
                    prePrimedPos = BlockPos.NULL_VECTOR
                }
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
            !world.isLiquid(blockTask.blockPos)) {
            blockTask.updateState(TaskState.DONE)
            return
        }

        when (blockTask.block) {
            material -> {
                if (currentBlock == material) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                } else if (currentBlock != Blocks.AIR && !world.isLiquid(blockTask.blockPos)) {
                    blockTask.updateState(TaskState.BREAK)
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
                if (!world.isLiquid(blockTask.blockPos)) {
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
                    if (containerTask.block == currentBlock) {
                        containerTask.updateState(TaskState.BREAK)
                    } else {
                        containerTask.updateState(TaskState.DONE)
                    }
                } else {
                    pendingTasks.remove(blockTask.blockPos)
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