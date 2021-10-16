package trombone.task

import HighwayTools.illegalPlacements
import HighwayTools.maxReach
import HighwayTools.placementSearch
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.world.PlaceInfo
import com.lambda.client.util.world.getNeighbourSequence
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.Pathfinder.startingBlockPos
import kotlin.random.Random

class BlockTask(
    val blockPos: BlockPos,
    var taskState: TaskState,
    var block: Block,
    var item: Item = Items.AIR
) {
    private var ranTicks = 0
    var stuckTicks = 0; private set
    var shuffle = 0; private set
    var startDistance = 0.0; private set
    var eyeDistance = 0.0; private set

    var sequence: List<PlaceInfo> = emptyList(); private set
    var isFiller = false
    var isLiquidSource = false
    var isSupport = false

    var isShulker = false
    var isOpen = false
    var isLoaded = false
    var itemID = 0
    var destroy = false
    var collect = true

    var timestamp = System.currentTimeMillis()
    var aabb = AxisAlignedBB(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)

    var toRemove = false

    fun updateState(state: TaskState) {
        if (state == taskState) return

        taskState = state
        timestamp = System.currentTimeMillis()
        if (state == TaskState.DONE || state == TaskState.PLACED || state == TaskState.BROKEN) {
            onUpdate()
        }
    }

    fun onTick() {
        ranTicks++
        if (ranTicks > taskState.stuckThreshold) {
            stuckTicks++
        }
    }

    fun onStuck(weight: Int = 1) {
        stuckTicks += weight
    }

    fun resetStuck() {
        stuckTicks = 0
    }

    fun updateTask(event: SafeClientEvent, eyePos: Vec3d) {
        when (taskState) {
            TaskState.PLACE, TaskState.LIQUID -> {
                sequence = event.getNeighbourSequence(blockPos, placementSearch, maxReach, !illegalPlacements)
            }
            else -> { }
        }

        startDistance = startingBlockPos.distanceTo(blockPos)
        eyeDistance = eyePos.distanceTo(blockPos)
    }

    fun updateLiquid(event: SafeClientEvent) {
        isLiquidSource = event.world.getBlockState(blockPos).let {
            it.block is BlockLiquid && it.getValue(BlockLiquid.LEVEL) == 0
        }
        isFiller = true
    }

    fun shuffle() {
        shuffle = Random.nextInt(0, 1000)
    }

    fun prettyPrint(): String {
        return "    ${block.localizedName}@(${blockPos.asString()}) State: $taskState Timings: (Threshold: ${taskState.stuckThreshold} Timeout: ${taskState.stuckTimeout}) Priority: ${taskState.ordinal} Stuck: $stuckTicks"
    }

    private fun onUpdate() {
        stuckTicks = 0
        ranTicks = 0
    }

    override fun toString(): String {
        return "Block: ${block.localizedName} @ Position: (${blockPos.asString()}) State: ${taskState.name}"
    }

    override fun equals(other: Any?) = this === other
        || (other is BlockTask
        && blockPos == other.blockPos)

    override fun hashCode() = blockPos.hashCode()
}