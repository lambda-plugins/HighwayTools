package trombone.interaction

import HighwayTools.debugLevel
import HighwayTools.dynamicDelay
import HighwayTools.placeDelay
import HighwayTools.taskTimeout
import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.items.blockBlacklist
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.onMainThreadSafe
import com.lambda.client.util.world.getHitVec
import com.lambda.client.util.world.getHitVecOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import trombone.IO.DebugLevel
import trombone.Trombone.module
import trombone.handler.Container.containerTask
import trombone.handler.Inventory.lastHitVec
import trombone.handler.Inventory.waitTicks
import trombone.task.BlockTask
import trombone.task.TaskState

object Place {
    var extraPlaceDelay = 0

    fun SafeClientEvent.placeBlock(blockTask: BlockTask) {
        when (blockTask.sequence.size) {
            0 -> {
                if (blockTask.taskState == TaskState.LIQUID) {
                    blockTask.updateState(TaskState.DONE)
                }
                if (debugLevel == DebugLevel.VERBOSE) {
                    LambdaMod.LOG.warn("${module.chatName} No neighbours found for ${blockTask.blockPos.asString()}")
                }
                if (blockTask == containerTask) {
                    MessageSendHelper.sendChatMessage("${module.chatName} Can't find neighbours for container task to place on")
                    blockTask.updateState(TaskState.DONE)
                }
                blockTask.onStuck(21)
                return
            }
            1 -> {
                val last = blockTask.sequence.last()
                lastHitVec = getHitVec(last.pos, last.side)

                placeBlockNormal(blockTask, last.pos, last.side)
            }
            else -> {
                // ToDo: Rewrite deep place
//                blockTask.sequence.forEach {
//                    addTaskToPending(it.pos, TaskState.PLACE, fillerMat)
//                }
            }
        }
    }

    private fun SafeClientEvent.placeBlockNormal(blockTask: BlockTask, placePos: BlockPos, side: EnumFacing) {
        val hitVecOffset = getHitVecOffset(side)
        val currentBlock = world.getBlockState(placePos).block

        waitTicks = if (dynamicDelay) {
            placeDelay + extraPlaceDelay
        } else {
            placeDelay
        }
        blockTask.updateState(TaskState.PENDING_PLACE)

        if (currentBlock in blockBlacklist) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }

        defaultScope.launch {
            delay(20L) // ToDo: Check if necessary
            onMainThreadSafe {
                val placePacket = CPacketPlayerTryUseItemOnBlock(placePos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
                connection.sendPacket(placePacket)
                player.swingArm(EnumHand.MAIN_HAND)
            }

            if (currentBlock in blockBlacklist) {
                delay(20L)
                onMainThreadSafe {
                    connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                }
            }

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_PLACE) {
                blockTask.updateState(TaskState.PLACE)
                if (dynamicDelay && extraPlaceDelay < 10) extraPlaceDelay += 1
            }
        }
    }
}