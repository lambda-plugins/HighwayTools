package trombone.interaction

import HighwayTools.alwaysBoth
import HighwayTools.breakDelay
import HighwayTools.illegalPlacements
import HighwayTools.instantMine
import HighwayTools.limitFactor
import HighwayTools.limitOrigin
import HighwayTools.maxBreaks
import HighwayTools.maxReach
import HighwayTools.taskTimeout
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.TpsCalculator
import com.lambda.client.util.math.isInSight
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.onMainThreadSafe
import com.lambda.client.util.threads.runSafeSuspend
import com.lambda.client.util.world.getHitVec
import com.lambda.client.util.world.getMiningSide
import com.lambda.client.util.world.getNeighbourSequence
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import net.minecraft.init.Blocks
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import trombone.handler.Liquid.handleLiquid
import trombone.handler.Player.LimitMode
import trombone.handler.Player.lastHitVec
import trombone.handler.Player.packetLimiter
import trombone.handler.Player.packetLimiterMutex
import trombone.handler.Player.waitTicks
import trombone.handler.Tasks.sortedTasks
import trombone.handler.Tasks.stateUpdateMutex
import trombone.task.BlockTask
import trombone.task.TaskState

object Break {
    var prePrimedPos = BlockPos.NULL_VECTOR!!
    var primedPos = BlockPos.NULL_VECTOR!!

    fun SafeClientEvent.mineBlock(blockTask: BlockTask) {
        val blockState = world.getBlockState(blockTask.blockPos)

        if (blockState.block == Blocks.FIRE) {
            val sides = getNeighbourSequence(blockTask.blockPos, 1, maxReach, !illegalPlacements)
            if (sides.isEmpty()) {
                blockTask.updateState(TaskState.PLACE)
                return
            }

            lastHitVec = getHitVec(sides.last().pos, sides.last().side)

            mineBlockNormal(blockTask, sides.last().side)
        } else {
            var side = getMiningSide(blockTask.blockPos) ?: run {
                blockTask.onStuck()
                return
            }

            if (blockTask.blockPos == primedPos && instantMine) {
                side = side.opposite
            }
            lastHitVec = getHitVec(blockTask.blockPos, side)

            if (blockState.getPlayerRelativeBlockHardness(player, world, blockTask.blockPos) > 2.8) {
                mineBlockInstant(blockTask, side)
            } else {
                mineBlockNormal(blockTask, side)
            }
        }
    }

    private fun mineBlockInstant(blockTask: BlockTask, side: EnumFacing) {
        waitTicks = breakDelay
        blockTask.updateState(TaskState.PENDING_BREAK)

        defaultScope.launch {
            packetLimiterMutex.withLock {
                packetLimiter.add(System.currentTimeMillis())
            }

            sendMiningPackets(blockTask.blockPos, side, start = true)

            if (maxBreaks > 1) {
                tryMultiBreak(blockTask)
            }

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_BREAK) {
                stateUpdateMutex.withLock {
                    blockTask.updateState(TaskState.BREAK)
                }
            }
        }
    }

    private suspend fun tryMultiBreak(blockTask: BlockTask) {
        runSafeSuspend {
            val eyePos = player.getPositionEyes(1.0f)
            val viewVec = lastHitVec.subtract(eyePos).normalize()
            var breakCount = 1

            for (task in sortedTasks) {
                if (breakCount >= maxBreaks) break

                val size = packetLimiterMutex.withLock {
                    packetLimiter.size
                }

                val limit = when (limitOrigin) {
                    LimitMode.FIXED -> 20.0f
                    LimitMode.SERVER -> TpsCalculator.tickRate
                }

                if (size > limit * limitFactor) break // Drop instant mine action when exceeded limit

                if (task == blockTask) continue
                if (task.taskState != TaskState.BREAK) continue
                if (world.getBlockState(task.blockPos).block != Blocks.NETHERRACK) continue

                val box = AxisAlignedBB(task.blockPos)
                val rayTraceResult = box.isInSight(eyePos, viewVec) ?: continue

                if (handleLiquid(task)) break

                breakCount++
                packetLimiterMutex.withLock {
                    packetLimiter.add(System.currentTimeMillis())
                }

                defaultScope.launch {
                    sendMiningPackets(task.blockPos, rayTraceResult.sideHit, start = true)

                    delay(50L * taskTimeout)
                    if (blockTask.taskState == TaskState.PENDING_BREAK) {
                        stateUpdateMutex.withLock {
                            blockTask.updateState(TaskState.BREAK)
                        }
                    }
                }
            }
        }
    }

    private fun mineBlockNormal(blockTask: BlockTask, side: EnumFacing) {
        defaultScope.launch {
            if (blockTask.taskState == TaskState.BREAK) {
                sendMiningPackets(blockTask.blockPos, side, start = true)
                blockTask.updateState(TaskState.BREAKING)
            } else {
                sendMiningPackets(blockTask.blockPos, side, stop = true)
            }
        }
    }

    private suspend fun sendMiningPackets(pos: BlockPos, side: EnumFacing, start: Boolean = false, stop: Boolean = false) {
        onMainThreadSafe {
            if (start || alwaysBoth) connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, side))
            if (stop || alwaysBoth) connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, side))
            player.swingArm(EnumHand.MAIN_HAND)
        }
    }
}