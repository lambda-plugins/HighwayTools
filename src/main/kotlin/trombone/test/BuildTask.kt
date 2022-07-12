package trombone.test

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import net.minecraft.block.Block
import net.minecraft.block.state.IBlockState
import net.minecraft.util.math.BlockPos

abstract class BuildTask(
    val blockPos: BlockPos,
    val targetBlock: Block,
    var isContainer: Boolean = false,
    var isSupport: Boolean = false,
    var isFiller: Boolean = false
) {
    abstract val priority: Int // low value is high priority
    abstract val timeout: Int
    abstract val SafeClientEvent.threshold: Int
    abstract val color: ColorHolder

    val timeStamp = System.currentTimeMillis()
    var stuck = 0

    /* check if requirements are met for the task */
    abstract fun SafeClientEvent.isValid(): Boolean

    /* check for changed circumstances, returns false when no update is necessary */
    abstract fun SafeClientEvent.update(): Boolean

    /* run the task */
    abstract fun SafeClientEvent.execute(): Boolean

    /* is successfully done */
    abstract fun SafeClientEvent.isDone(): Boolean

    /*  */
    fun onStuck() {
        stuck++
    }

    /* helper functions */
    val SafeClientEvent.currentBlockState: IBlockState get() = world.getBlockState(blockPos)
    val SafeClientEvent.currentBlock: Block get() = currentBlockState.block

    abstract override fun toString(): String
}