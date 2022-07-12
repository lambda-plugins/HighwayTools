package trombone.test.tasks

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import net.minecraft.block.Block
import net.minecraft.util.math.BlockPos
import trombone.test.BuildTask

class PlaceTask(
    blockPos: BlockPos,
    targetBlock: Block,
    isContainer: Boolean = false,
    isSupport: Boolean = false,
    isFiller: Boolean = false,
    var isLiquid: Boolean = false
) : BuildTask(blockPos, targetBlock, isContainer, isSupport, isFiller) {
    override val priority: Int
        get() = 2
    override val timeout: Int
        get() = 20
    override val SafeClientEvent.threshold: Int
        get() = 20
    override val color: ColorHolder
        get() = ColorHolder(35, 188, 254)

    override fun SafeClientEvent.isValid(): Boolean {
        TODO("Not yet implemented")
    }

    override fun SafeClientEvent.update(): Boolean {
        TODO("Not yet implemented")
    }

    override fun SafeClientEvent.execute(): Boolean {
        TODO("Not yet implemented")
    }

    override fun SafeClientEvent.isDone(): Boolean {
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        TODO("Not yet implemented")
    }
}