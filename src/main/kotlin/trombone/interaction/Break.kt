package trombone.interaction

import HighwayTools.breakDelay
import HighwayTools.illegalPlacements
import HighwayTools.instantMine
import HighwayTools.interactionLimit
import HighwayTools.maxReach
import HighwayTools.miningSpeedFactor
import HighwayTools.multiBreak
import HighwayTools.packetFlood
import HighwayTools.taskTimeout
import com.lambda.client.commons.extension.ceilToInt
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.math.isInSight
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.onMainThreadSafe
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.world.getHitVec
import com.lambda.client.util.world.getMiningSide
import com.lambda.client.util.world.getNeighbour
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.init.Blocks
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import trombone.handler.Inventory.lastHitVec
import trombone.handler.Inventory.packetLimiter
import trombone.handler.Inventory.waitTicks
import trombone.handler.Liquid.handleLiquid
import trombone.task.BlockTask
import trombone.task.TaskManager.tasks
import trombone.task.TaskState
import kotlin.math.ceil

object Break {
    var prePrimedPos = BlockPos.NULL_VECTOR!!
    var primedPos = BlockPos.NULL_VECTOR!!

    fun SafeClientEvent.mineBlock(blockTask: BlockTask) {
        val blockState = world.getBlockState(blockTask.blockPos)

        if (blockState.block == Blocks.FIRE) {
            getNeighbour(blockTask.blockPos, 1, maxReach, !illegalPlacements)?.let {
                lastHitVec = getHitVec(it.pos, it.side)

                extinguishFire(blockTask, it.pos, it.side)
            } ?: run {
                blockTask.updateState(TaskState.PLACE)
            }
        } else {
            val ticksNeeded = ceil((1 / blockState.getPlayerRelativeBlockHardness(player, world, blockTask.blockPos)) * miningSpeedFactor).toInt()

            var side = getMiningSide(blockTask.blockPos) ?: run {
                blockTask.onStuck()
                return
            }

            if (blockTask.blockPos == primedPos && instantMine) {
                side = side.opposite
            }

            lastHitVec = getHitVec(blockTask.blockPos, side)

            if (blockTask.ticksMined > ticksNeeded * 1.1 &&
                blockTask.taskState == TaskState.BREAKING) {
                blockTask.updateState(TaskState.BREAK)
                blockTask.ticksMined = 0
            }

            if (ticksNeeded == 1 || player.capabilities.isCreativeMode) {
                mineBlockInstant(blockTask, side)
            } else {
                mineBlockNormal(blockTask, side, ticksNeeded)
            }
        }

        blockTask.ticksMined += 1
    }

    private fun mineBlockInstant(blockTask: BlockTask, side: EnumFacing) {
        waitTicks = breakDelay
        blockTask.updateState(TaskState.PENDING_BREAK)

        defaultScope.launch {
            sendMiningPackets(blockTask.blockPos, side, start = true)

            if (multiBreak) tryMultiBreak(blockTask)

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_BREAK) {
                blockTask.updateState(TaskState.BREAK)
            }
        }
    }

    private fun tryMultiBreak(blockTask: BlockTask) {
        runSafe {
            val eyePos = player.getPositionEyes(1.0f)
            val viewVec = lastHitVec.subtract(eyePos).normalize()

            tasks.values.filter {
                it.taskState == TaskState.BREAK
                    && it != blockTask
            }.forEach { task ->
                val relativeHardness = world.getBlockState(task.blockPos).getPlayerRelativeBlockHardness(player, world, task.blockPos)
                if (ceil((1 / relativeHardness) * miningSpeedFactor).ceilToInt() > 1) return@forEach

                if (packetLimiter.size > interactionLimit || handleLiquid(task)) return@runSafe

                val box = AxisAlignedBB(task.blockPos)
                val rayTraceResult = box.isInSight(eyePos, viewVec, range = maxReach.toDouble(), tolerance = 0.0)
                    ?: return@forEach

                task.updateState(TaskState.PENDING_BREAK)

                defaultScope.launch {
                    sendMiningPackets(task.blockPos, rayTraceResult.sideHit, start = true)

                    delay(50L * taskTimeout)
                    if (task.taskState == TaskState.PENDING_BREAK) {
                        task.updateState(TaskState.BREAK)
                    }
                }
            }
        }
    }

    private fun mineBlockNormal(blockTask: BlockTask, side: EnumFacing, ticks: Int) {
        defaultScope.launch {
            if (blockTask.taskState == TaskState.BREAK) {
                blockTask.updateState(TaskState.BREAKING)
                sendMiningPackets(blockTask.blockPos, side, start = true)
            } else {
                if (blockTask.ticksMined >= ticks) {
                    sendMiningPackets(blockTask.blockPos, side, stop = true)
                } else {
                    sendMiningPackets(blockTask.blockPos, side)
                }
            }
        }
    }

    private fun extinguishFire(blockTask: BlockTask, pos: BlockPos, side: EnumFacing) {
        waitTicks = breakDelay
        blockTask.updateState(TaskState.PENDING_BREAK)

        defaultScope.launch {
            sendMiningPackets(pos, side, start = true, abort = true)

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_BREAK) {
                blockTask.updateState(TaskState.BREAK)
            }
        }
    }

    private suspend fun sendMiningPackets(pos: BlockPos, side: EnumFacing, start: Boolean = false, stop: Boolean = false, abort: Boolean = false) {
        packetLimiter.add(System.currentTimeMillis())
        onMainThreadSafe {
            if (start || packetFlood) {
                connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, side))
            }
            if (abort) {
                connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.ABORT_DESTROY_BLOCK, pos, side))
            }
            if (stop || packetFlood) {
                connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, side))
            }
            player.swingArm(EnumHand.MAIN_HAND)
        }
    }
}