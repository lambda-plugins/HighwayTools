package trombone.test.tasks

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.math.CoordinateConverter.asString
import net.minecraft.block.Block
import net.minecraft.util.math.BlockPos
import trombone.test.BuildTask
import trombone.test.TaskProcessor

class DoneTask(blockPos: BlockPos, targetBlock: Block) : BuildTask(blockPos, targetBlock) {
    override val priority: Int
        get() = 0
    override val timeout: Int
        get() = 0
    override val SafeClientEvent.threshold: Int
        get() = 0
    override val color: ColorHolder
        get() = ColorHolder(50, 50, 50)

    private enum class State(val colorHolder: ColorHolder, val prioOffset: Int) {
        INIT(ColorHolder(222, 0, 0), 0),
        BREAKING(ColorHolder(240, 222, 60), -100),
        PENDING(ColorHolder(42, 0, 0), 20),
        ACCEPTED(ColorHolder(), 0)
    }

    override fun SafeClientEvent.isValid() = true

    override fun SafeClientEvent.update() = true

    override fun SafeClientEvent.execute(): Boolean {
        TaskProcessor.tasks.remove(blockPos)

        return true
    }

    override fun SafeClientEvent.isDone() = true

    override fun toString() = "(blockPos=(${blockPos.asString()}) targetBlock=${targetBlock.localizedName})"
}