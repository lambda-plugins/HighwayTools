package trombone.test.task

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.math.CoordinateConverter.asString
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.block.state.IBlockState
import net.minecraft.item.Item
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

abstract class BuildTask(
    val blockPos: BlockPos,
    val targetBlock: Block,
    var isFillerTask: Boolean = false,
    var isContainerTask: Boolean = false,
    var isSupportTask: Boolean = false
) {
    abstract var priority: Int // low value is high priority
    abstract val timeout: Int
    abstract var threshold: Int
    abstract val color: ColorHolder
    abstract var hitVec3d: Vec3d

    val timeStamp = System.currentTimeMillis()
    var timeTicking = 0
    var timesFailed = 0
    var aabb = AxisAlignedBB(BlockPos.ORIGIN)
    private var debugInfo: MutableList<Pair<String, String>> = mutableListOf()

    val itemsToRestock: MutableList<Item> = mutableListOf()
    var destroyAfterPlace = false

    /**
     * checks if requirements are met for the task
     * @return [Boolean] is true when all requirements are met
     */
    abstract fun SafeClientEvent.isValid(): Boolean

    /**
     * checks for changed circumstances
     * @return [Boolean] is true when changes were made and next task needs to be reconsidered
     */
    abstract fun SafeClientEvent.update(): Boolean

    /**
     * executes the task
     */
    abstract fun SafeClientEvent.execute()

    fun SafeClientEvent.runUpdate(): Boolean {
        aabb = axisAlignedBB
        debugInfo = gatherAllDebugInfo()

        return update()
    }

    fun SafeClientEvent.runExecute() {
        timeTicking++
        execute()
    }

    private fun SafeClientEvent.gatherAllDebugInfo(): MutableList<Pair<String, String>> {
        val info: MutableList<Pair<String, String>> = mutableListOf()

        info.add(Pair("blockPos", blockPos.asString()))
        info.add(Pair("targetBlock", targetBlock.localizedName))
        info.add(Pair("isFillerTask", isFillerTask.toString()))
        info.add(Pair("isContainerTask", isContainerTask.toString()))
        info.add(Pair("isSupportTask", isSupportTask.toString()))
        info.add(Pair("priority", priority.toString()))
        info.add(Pair("timeout", timeout.toString()))
        info.add(Pair("threshold", threshold.toString()))
        info.add(Pair("color", color.toString()))
        info.add(Pair("timeStamp", timeStamp.toString()))
        info.add(Pair("timeTicking", timeTicking.toString()))

        info.addAll(gatherDebugInfo())

        return info
    }

    override fun toString() = "$javaClass blockPos=(${blockPos.asString()}) targetBlock=${targetBlock.localizedName}${if (isFillerTask) " isFillerTask" else ""}${if (isContainerTask) " isContainerTask" else ""}${if (isSupportTask) " isSupportTask" else ""} ${gatherInfoToString()}"

    /* helper functions */
    val SafeClientEvent.currentBlockState: IBlockState get() = world.getBlockState(blockPos)
    val SafeClientEvent.currentBlock: Block get() = currentBlockState.block
    val SafeClientEvent.isLiquidBlock get() = currentBlock is BlockLiquid
    private val SafeClientEvent.axisAlignedBB: AxisAlignedBB get() = currentBlockState.getSelectedBoundingBox(world, blockPos).also { aabb = it }

    abstract fun gatherInfoToString(): String

    abstract fun SafeClientEvent.gatherDebugInfo(): MutableList<Pair<String, String>>
}