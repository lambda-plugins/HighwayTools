package trombone.handler

import HighwayTools.anonymizeStats
import HighwayTools.debugMessages
import HighwayTools.fillerMat
import HighwayTools.maxReach
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.text.MessageSendHelper
import net.minecraft.block.BlockLiquid
import net.minecraft.util.EnumFacing
import trombone.IO
import trombone.Trombone.module
import trombone.handler.Tasks.safeTask
import trombone.handler.Tasks.tasks
import trombone.task.BlockTask
import trombone.task.TaskState

object Liquid {
    fun SafeClientEvent.handleLiquid(blockTask: BlockTask): Boolean {
        var foundLiquid = false

        for (side in EnumFacing.values()) {
            if (side == EnumFacing.DOWN) continue
            val neighbourPos = blockTask.blockPos.offset(side)

            if (world.getBlockState(neighbourPos).block !is BlockLiquid) continue

            if (player.distanceTo(neighbourPos) > maxReach) {
                if (debugMessages == IO.DebugMessages.ALL) {
                    if (!anonymizeStats) {
                        MessageSendHelper.sendChatMessage("${module.chatName} Liquid@(${neighbourPos.asString()}) out of reach (${player.distanceTo(neighbourPos)})")
                    } else {
                        MessageSendHelper.sendChatMessage("${module.chatName} Liquid out of reach (${player.distanceTo(neighbourPos)})")
                    }
                }
                blockTask.updateState(TaskState.DONE)
                return true
            }

            foundLiquid = true

            tasks[neighbourPos]?.let {
                updateLiquidTask(it)
            } ?: run {
                safeTask(neighbourPos, TaskState.LIQUID, fillerMat).updateLiquid(this)
            }
        }

        return foundLiquid
    }

    fun SafeClientEvent.updateLiquidTask(blockTask: BlockTask) {
        blockTask.updateState(TaskState.LIQUID)
        blockTask.updateLiquid(this)
    }
}