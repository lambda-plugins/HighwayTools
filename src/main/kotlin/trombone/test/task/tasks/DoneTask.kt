package trombone.test.task.tasks

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.math.CoordinateConverter.asString
import net.minecraft.block.Block
import net.minecraft.util.math.BlockPos
import trombone.test.task.BuildTask
import trombone.test.task.TaskProcessor

class DoneTask(blockPos: BlockPos,
               targetBlock: Block,
               isFiller: Boolean = false,
               isContainer: Boolean = false,
               isSupport: Boolean = false
) : BuildTask(blockPos, targetBlock, isFiller, isContainer, isSupport) {
    override var priority = 0
    override val timeout = 0
    override var threshold = 0
    override val color = ColorHolder(50, 50, 50)

    override fun SafeClientEvent.isValid() = true

    override fun SafeClientEvent.update() = true

    override fun SafeClientEvent.execute() {
        TaskProcessor.tasks.remove(blockPos)
    }

    override fun gatherInfoToString() = ""

    override fun SafeClientEvent.gatherDebugInfo(): MutableList<Pair<String, String>> = mutableListOf()
}