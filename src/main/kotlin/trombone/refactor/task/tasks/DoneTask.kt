package trombone.refactor.task.tasks

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import net.minecraft.block.Block
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.refactor.task.BuildTask
import trombone.refactor.task.TaskProcessor

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
    override var hitVec3d: Vec3d? = null

    override fun SafeClientEvent.isValid() = true

    override fun SafeClientEvent.update() = true

    override fun SafeClientEvent.execute() {
        TaskProcessor.tasks.remove(blockPos)
    }

    override fun gatherInfoToString() = ""

    override fun SafeClientEvent.gatherDebugInfo(): MutableList<Pair<String, String>> = mutableListOf()
}