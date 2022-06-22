package trombone.task

import HighwayTools.anonymizeStats
import HighwayTools.breakDelay
import HighwayTools.debugLevel
import HighwayTools.dynamicDelay
import HighwayTools.fakeSounds
import HighwayTools.fastFill
import HighwayTools.fillerMat
import HighwayTools.ignoreBlocks
import HighwayTools.interactionLimit
import HighwayTools.keepFreeSlots
import HighwayTools.leaveEmptyShulkers
import HighwayTools.material
import HighwayTools.mode
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.util.TickTimer
import com.lambda.client.util.items.*
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.world.getCollisionBox
import com.lambda.client.util.world.getHitVec
import com.lambda.client.util.world.getHitVecOffset
import com.lambda.client.util.world.isPlaceable
import net.minecraft.block.BlockLiquid
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.init.Blocks
import net.minecraft.item.ItemPickaxe
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import trombone.*
import trombone.IO.disableError
import trombone.Pathfinder.moveState
import trombone.Pathfinder.shouldBridge
import trombone.Trombone.module
import trombone.handler.Container
import trombone.handler.Container.containerTask
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Inventory
import trombone.handler.Inventory.getEjectSlot
import trombone.handler.Inventory.moveToInventory
import trombone.handler.Inventory.swapOrMoveBestTool
import trombone.handler.Inventory.swapOrMoveBlock
import trombone.handler.Liquid.handleLiquid
import trombone.handler.Liquid.updateLiquidTask
import trombone.interaction.Break
import trombone.interaction.Break.mineBlock
import trombone.interaction.Place
import trombone.interaction.Place.placeBlock

object TaskExecutor {
    private val restockTimer = TickTimer()

    fun SafeClientEvent.doTask(blockTask: BlockTask, updateOnly: Boolean = false) {
        if (!updateOnly) blockTask.onTick()

        when (blockTask.taskState) {
            TaskState.RESTOCK -> {
                if (!updateOnly) doRestock()
            }
            TaskState.PICKUP -> {
                if (!updateOnly) doPickup()
            }
            TaskState.OPEN_CONTAINER -> {
                if (!updateOnly) doOpenContainer()
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
            TaskState.DONE -> { /* do nothing */ }
        }
    }

    private fun SafeClientEvent.doRestock() {
        val container = player.openContainer

        if (mc.currentScreen !is GuiContainer && !containerTask.isLoaded) {
            containerTask.updateState(TaskState.OPEN_CONTAINER)
            return
        }

        if (container.inventorySlots.size != 63) {
            disableError("Inventory container changed. Current: ${player.openContainer.windowId} and saved ${container.windowId}")
            return
        }

        if (leaveEmptyShulkers
            && containerTask.isShulker()
            && container.getSlots(0..26).all {
                it.stack.isEmpty
                    || InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
            }
        ) {
            if (debugLevel != IO.DebugLevel.OFF) {
                if (!anonymizeStats) {
                    MessageSendHelper.sendChatMessage("${Trombone.module.chatName} Left empty ${containerTask.targetBlock.localizedName}@(${containerTask.blockPos.asString()})")
                } else {
                    MessageSendHelper.sendChatMessage("${Trombone.module.chatName} Left empty ${containerTask.targetBlock.localizedName}")
                }
            }

            containerTask.isOpen = false
            player.closeScreen()
            containerTask.updateState(TaskState.DONE)
            moveState = Pathfinder.MovementState.RUNNING
            return
        }

        val freeSlots = container.getSlots(27..62).count {
            InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
                || it.stack.isEmpty
        } - 1 - keepFreeSlots

        if (containerTask.stopPull || freeSlots < 1) {
            containerTask.updateState(TaskState.BREAK)
            containerTask.isOpen = false
            player.closeScreen()
            return
        }

        container.getSlots(0..26).firstItem(containerTask.item)?.let {
            moveToInventory(it, container)
            containerTask.stacksPulled++
            containerTask.stopPull = true
            if (fastFill) {
                if (mode == Trombone.Structure.TUNNEL && containerTask.item is ItemPickaxe) {
                    containerTask.stopPull = false
                } else if (mode != Trombone.Structure.TUNNEL && containerTask.item == material.item) {
                    containerTask.stopPull = false
                }
            }
        } ?: run {
            if (containerTask.stacksPulled == 0) {
                Container.getShulkerWith(container.getSlots(0..26), containerTask.item)?.let {
                    moveToInventory(it, container)
                    containerTask.stopPull = true
                } ?: run {
                    disableError("No ${containerTask.item.registryName} left in any container.")
                }
            } else {
                containerTask.updateState(TaskState.BREAK)
                containerTask.isOpen = false
                player.closeScreen()
            }
        }
    }

    private fun SafeClientEvent.doPickup() {
        if (getCollectingPosition() == null) {
            containerTask.updateState(TaskState.DONE)
            moveState = Pathfinder.MovementState.RUNNING
            return
        }

        if (player.inventorySlots.firstEmpty() == null && restockTimer.tick(20)) {
            getEjectSlot()?.let {
                throwAllInSlot(module, it)
            }
        } else {
            containerTask.onStuck()
        }
    }

    private fun SafeClientEvent.doOpenContainer() {
        moveState = Pathfinder.MovementState.RESTOCK

        if (containerTask.isOpen) {
            containerTask.updateState(TaskState.RESTOCK)
            return
        }

        if (Container.shulkerOpenTimer.tick(20)) {
            val center = containerTask.blockPos.toVec3dCenter()
            val diff = player.getPositionEyes(1f).subtract(center)
            val normalizedVec = diff.normalize()

            val side = EnumFacing.getFacingFromVector(normalizedVec.x.toFloat(), normalizedVec.y.toFloat(), normalizedVec.z.toFloat())
            val hitVecOffset = getHitVecOffset(side)

            Inventory.lastHitVec = getHitVec(containerTask.blockPos, side)

            connection.sendPacket(CPacketPlayerTryUseItemOnBlock(containerTask.blockPos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat()))
            player.swingArm(EnumHand.MAIN_HAND)
        }
    }

    private fun SafeClientEvent.doBreaking(blockTask: BlockTask, updateOnly: Boolean) {
        val block = world.getBlockState(blockTask.blockPos).block

        if (block == Blocks.AIR) {
            Inventory.waitTicks =
                breakDelay
            blockTask.updateState(TaskState.BROKEN)
            return
        }

        if (block is BlockLiquid) {
            updateLiquidTask(blockTask)
            return
        }

        if (!updateOnly
            && swapOrMoveBestTool(blockTask)
            && Inventory.packetLimiter.size < interactionLimit
        ) {
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doBroken(blockTask: BlockTask) {
        if (world.getBlockState(blockTask.blockPos).block != Blocks.AIR) {
            blockTask.updateState(TaskState.BREAK)
            return
        }

        Statistics.totalBlocksBroken++

        TaskManager.tasks.forEach { (_, task) ->
            if (task.taskState == TaskState.BREAK) task.resetStuck()
        }

        // Instant break exploit
        if (blockTask.blockPos == Break.prePrimedPos) {
            Break.primedPos = Break.prePrimedPos
            Break.prePrimedPos = BlockPos.NULL_VECTOR
        }

        Statistics.simpleMovingAverageBreaks.add(System.currentTimeMillis())

        // Sound
        if (fakeSounds) {
            val soundType = blockTask.targetBlock.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
            world.playSound(player, blockTask.blockPos, soundType.breakSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
        }

        if (blockTask == containerTask) {
            if (containerTask.collect) {
                moveState = Pathfinder.MovementState.PICKUP
                blockTask.updateState(TaskState.PICKUP)
            } else {
                blockTask.updateState(TaskState.DONE)
            }
            return
        }

        if (blockTask.targetBlock == Blocks.AIR) {
            blockTask.updateState(TaskState.DONE)
        } else {
            blockTask.updateState(TaskState.PLACE)
        }
    }

    private fun SafeClientEvent.doPlaced(blockTask: BlockTask) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        when {
            blockTask.targetBlock == currentBlock && currentBlock != Blocks.AIR -> {
                Statistics.totalBlocksPlaced++
                Break.prePrimedPos = blockTask.blockPos
                Statistics.simpleMovingAveragePlaces.add(System.currentTimeMillis())

                if (
                    dynamicDelay && Place.extraPlaceDelay > 0) Place.extraPlaceDelay /= 2

                if (blockTask == containerTask) {
                    if (containerTask.destroy) {
                        containerTask.updateState(TaskState.BREAK)
                    } else {
                        containerTask.updateState(TaskState.OPEN_CONTAINER)
                    }
                } else {
                    blockTask.updateState(TaskState.DONE)
                }

                TaskManager.tasks.values.filter { it.taskState == TaskState.PLACE }.forEach { it.resetStuck() }

                if (fakeSounds) {
                    val soundType = currentBlock.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                    world.playSound(player, blockTask.blockPos, soundType.placeSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                }
            }
            blockTask.targetBlock == currentBlock && currentBlock == Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            blockTask.targetBlock == Blocks.AIR && currentBlock != Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            else -> {
                blockTask.updateState(TaskState.PLACE)
            }
        }
    }

    private fun SafeClientEvent.doBreak(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (ignoreBlocks.contains(currentBlock.registryName.toString())
            && !blockTask.isShulker()
            && !Blueprint.isInsideBlueprintBuild(blockTask.blockPos)
            || currentBlock in arrayOf(Blocks.PORTAL, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.BEDROCK)
        ) {
            blockTask.updateState(TaskState.DONE)
            return
        }

        // TODO: Fix this
//        if (blockTask.targetBlock == fillerMat
//            && world.getBlockState(blockTask.blockPos.up()).block == material
//            || (!world.isPlaceable(blockTask.blockPos)
//                && world.getCollisionBox(blockTask.blockPos) != null)
//        ) {
//            blockTask.updateState(TaskState.DONE)
//            return
//        }

        when (blockTask.targetBlock) {
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
                if (blockTask.targetBlock == Blocks.AIR) {
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

        if (!updateOnly
            && player.onGround
            && swapOrMoveBestTool(blockTask)
            && !handleLiquid(blockTask)
            && Inventory.packetLimiter.size < interactionLimit
        ) {
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doPlace(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (shouldBridge()
            && moveState != Pathfinder.MovementState.RESTOCK
            && player.positionVector.distanceTo(Pathfinder.currentBlockPos) < 1) {
            moveState = Pathfinder.MovementState.BRIDGE
        }

        if (blockTask.taskState == TaskState.LIQUID
            && world.getBlockState(blockTask.blockPos).block !is BlockLiquid) {
            blockTask.updateState(TaskState.DONE)
            return
        }

        when (blockTask.targetBlock) {
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
                } else if (currentBlock != fillerMat
                    && mode == Trombone.Structure.HIGHWAY
                    && world.getBlockState(blockTask.blockPos.up()).block == material) {
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

        if (updateOnly) return

        if (!world.isPlaceable(blockTask.blockPos)) {
            if (debugLevel == IO.DebugLevel.VERBOSE) {
                if (!anonymizeStats) {
                    MessageSendHelper.sendChatMessage("${Trombone.module.chatName} Invalid place position @(${blockTask.blockPos.asString()}) Removing task")
                } else {
                    MessageSendHelper.sendChatMessage("${Trombone.module.chatName} Invalid place position. Removing task")
                }
            }

            if (blockTask == containerTask) {
                MessageSendHelper.sendChatMessage("${Trombone.module.chatName} Failed container task. Trying to break block.")
                containerTask.updateState(TaskState.BREAK)
            } else {
                TaskManager.tasks.remove(blockTask.blockPos)
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