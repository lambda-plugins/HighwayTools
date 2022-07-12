package trombone.test.tasks

import HighwayTools.debugLevel
import HighwayTools.fillerMat
import HighwayTools.ignoreBlocks
import HighwayTools.illegalPlacements
import HighwayTools.interactionLimit
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.miningSpeedFactor
import HighwayTools.placementSearch
import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.world.getCollisionBox
import com.lambda.client.util.world.getNeighbourSequence
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos
import trombone.IO
import trombone.blueprint.BlueprintGenerator
import trombone.test.BuildTask
import trombone.test.TaskProcessor.addTask
import trombone.test.TaskProcessor.convertTo
import kotlin.math.ceil

class BreakTask(
    blockPos: BlockPos,
    targetBlock: Block,
    isContainer: Boolean = false,
    isSupport: Boolean = false,
    isFiller: Boolean = false
) : BuildTask(blockPos, targetBlock, isContainer, isSupport, isFiller) {
    override val priority: Int
        get() = 1 + state.prioOffset
    override val timeout: Int
        get() = 20
    override val SafeClientEvent.threshold: Int
        get() = 1 + getTicksNeeded()
    override val color: ColorHolder
        get() = state.colorHolder

    private var toolToUse = Items.AIR

    private enum class State(val colorHolder: ColorHolder, val prioOffset: Int) {
        INIT(ColorHolder(222, 0, 0), 0),
        TOOL(),
        BREAKING(ColorHolder(240, 222, 60), -100),
        PENDING(ColorHolder(42, 0, 0), 20),
        ACCEPTED(ColorHolder(), 0)
    }

    private val state = State.INIT

    override fun SafeClientEvent.isValid() = !update()
        && player.onGround
        && packetLimiter.size < interactionLimit

    override fun SafeClientEvent.update(): Boolean {
        var wasUpdated = true

        when {
            shouldBeIgnored() || isIllegal() -> {
                convertTo<DoneTask>()
            }
            currentBlock == targetBlock -> {
                convertTo<DoneTask>()
            }
            currentBlock is BlockLiquid -> {
                convertTo<PlaceTask>(isLiquid = true)
            }
            isSupport && alreadyIsSupported() -> {
                convertTo<DoneTask>()
            }
            isFiller && alreadyIsFilled() -> {
                convertTo<DoneTask>()
            }
            else -> {
                wasUpdated = hasLiquidNeighbours()
            }
        }

        return wasUpdated
    }

    override fun SafeClientEvent.execute(): Boolean {
        if (!isValid()) return false



        return true
    }

    override fun SafeClientEvent.isDone(): Boolean {
        return currentBlock == Blocks.AIR
    }

    private fun SafeClientEvent.hasLiquidNeighbours(): Boolean {
        EnumFacing.values()
            .filter {
                it != EnumFacing.DOWN
                    && world.getBlockState(blockPos.offset(it)).block is BlockLiquid
            }.forEach {
                val neighbourPos = blockPos.offset(it)

                if (getNeighbourSequence(neighbourPos, placementSearch, maxReach, !illegalPlacements).isEmpty()) {
                    if (debugLevel == IO.DebugLevel.VERBOSE) {
                        LambdaMod.LOG.info("[Trombone] Skipping out of range liquid block at ${neighbourPos.asString()}. Ignoring task ${this@BreakTask}")
                    }

                    convertTo<DoneTask>()
                } else {
                    addTask(PlaceTask(neighbourPos, fillerMat, isFiller = true, isLiquid = true))
                }

                return true
            }
        return false
    }

    private fun SafeClientEvent.getTicksNeeded(): Int {
        return ceil((1 / currentBlockState.getPlayerRelativeBlockHardness(player, world, blockPos)) * miningSpeedFactor).toInt()
    }

    private fun SafeClientEvent.shouldBeIgnored() = ignoreBlocks.contains(currentBlock.registryName.toString())
        && !isContainer
        && !BlueprintGenerator.isInsideBlueprintBuild(blockPos) //ToDo

    private fun SafeClientEvent.alreadyIsSupported() = world.getBlockState(blockPos.up()).block == material

    private fun SafeClientEvent.alreadyIsFilled() = world.getCollisionBox(blockPos) != null

    private fun SafeClientEvent.isIllegal() = currentBlockState.getBlockHardness(world, blockPos) == -1.0f

    override fun toString() = "(${javaClass} blockPos=(${blockPos.asString()}), targetBlock=${targetBlock.localizedName}, )"
}