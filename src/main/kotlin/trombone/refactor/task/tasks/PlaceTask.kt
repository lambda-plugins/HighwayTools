package trombone.refactor.task.tasks

import HighwayTools.dynamicDelay
import HighwayTools.ejectList
import HighwayTools.fakeSounds
import HighwayTools.fillerMat
import HighwayTools.illegalPlacements
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.placeDelay
import HighwayTools.placementSearch
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.items.*
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.world.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.block.Block
import net.minecraft.block.Block.getBlockFromName
import net.minecraft.block.BlockLiquid
import net.minecraft.block.state.IBlockState
import net.minecraft.inventory.Slot
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.Pathfinder
import trombone.Pathfinder.moveState
import trombone.Pathfinder.shouldBridge
import trombone.Statistics
import trombone.Trombone.module
import trombone.refactor.task.RestockHandler.handleRestock
import trombone.refactor.task.BuildTask
import trombone.refactor.task.TaskProcessor
import trombone.refactor.task.TaskProcessor.addTask
import trombone.refactor.task.TaskProcessor.convertTo
import trombone.refactor.task.TaskProcessor.interactionLimitNotReached
import trombone.refactor.task.TaskProcessor.waitPenalty

class PlaceTask(
    blockPos: BlockPos,
    targetBlock: Block,
    isFillerTask: Boolean = false,
    isContainerTask: Boolean = false,
    isSupportTask: Boolean = false
) : BuildTask(blockPos, targetBlock, isFillerTask, isContainerTask, isSupportTask) {
    private var state = State.INVALID
    var placeInfo: PlaceInfo? = null
    private val SafeClientEvent.isLiquidSource get() = isLiquidBlock && currentBlockState.getValue(BlockLiquid.LEVEL) == 0
    private val SafeClientEvent.placeInfoSequence get() = getNeighbourSequence(blockPos, placementSearch, maxReach, !illegalPlacements)

    override var priority = 2
    override val timeout = 20
    override var threshold = 20
    override val color = state.colorHolder
    override var hitVec3d: Vec3d? = null

    enum class State(val colorHolder: ColorHolder, val prioOffset: Int) {
        INVALID(ColorHolder(16, 74, 94), 10),
        VALID(ColorHolder(35, 188, 254), 0),
        GET_BLOCK(ColorHolder(252, 3, 207), 0),
        PLACE(ColorHolder(35, 188, 254), 0),
        PENDING(ColorHolder(42, 0, 0), 20),
        ACCEPTED(ColorHolder(53, 222, 66), 0)
    }

    override fun SafeClientEvent.isValid() =
        interactionLimitNotReached
            && placeInfo != null
            && world.checkNoEntityCollision(aabb, player)

    override fun SafeClientEvent.update(): Boolean {
        var wasUpdated = true

        priority = 2 + state.prioOffset + if (isLiquidSource) 10 else 0
        if (placeInfoSequence.size == 1) placeInfo = placeInfoSequence.firstOrNull()
        hitVec3d = placeInfo?.hitVec

        if (isValid()
            && state == State.INVALID
        ) state = State.VALID

        when {
            currentBlock == targetBlock -> {
                convertTo<DoneTask>()
            }
            isSupportTask && alreadyIsSupported() -> {
                convertTo<DoneTask>()
            }
            isFillerTask && alreadyIsFilled() -> {
                convertTo<DoneTask>()
            }
            !currentBlockState.isReplaceable -> {
                convertTo<BreakTask>()
            }
            else -> {
                wasUpdated = false
            }
        }

        return wasUpdated
    }

    override fun SafeClientEvent.execute() {
        when (state) {
            State.INVALID -> {
                if (placeInfoSequence.isNotEmpty()) {
                    placeInfoSequence.filter {
                        !TaskProcessor.tasks.containsKey(it.placedPos)
                    }.forEach {
                        addTask(PlaceTask(it.placedPos, fillerMat, isFillerTask = true))
                    }
                    return
                }

                if (shouldBridge()) moveState = Pathfinder.MovementState.BRIDGE
            }
            State.VALID -> {
                state = State.GET_BLOCK
                execute()
            }
            State.GET_BLOCK -> {
                if (equipBlockToPlace()) {
                    state = State.PLACE
                    execute()
                }
            }
            State.PLACE -> {
                placeInfo?.let {
                    TaskProcessor.waitTicks = placeDelay + waitPenalty

                    state = State.PENDING

                    sendPlacingPackets(it.placedPos, it.side, getHitVecOffset(it.side))
                }
            }
            State.PENDING -> {
                /* Wait */
            }
            State.ACCEPTED -> {
                Statistics.totalBlocksPlaced++
                Statistics.simpleMovingAveragePlaces.add(System.currentTimeMillis())

                if (fakeSounds) {
                    val soundType = currentBlock.soundType
                    world.playSound(
                        player,
                        blockPos,
                        soundType.placeSound,
                        SoundCategory.BLOCKS,
                        (soundType.getVolume() + 1.0f) / 2.0f,
                        soundType.getPitch() * 0.8f
                    )
                }

                TaskProcessor.tasks.values.filterIsInstance<PlaceTask>().forEach {
                    it.timesFailed = 0
                }

                waitPenalty /= 2

                if (isContainerTask) {
                    if (destroyAfterPlace) {
                        convertTo<BreakTask>()
                    } else {
                        convertTo<RestockTask>()
                    }
                    return
                }

                convertTo<DoneTask>()
            }
        }
    }

    private fun SafeClientEvent.equipBlockToPlace(): Boolean {
        if (isContainerTask && slotToUseForPlace != null) {
            swapToSlotOrMove(slotToUseForPlace)
        }

        if (swapToItemOrMove(module, targetBlock.item)) {
            return true
        }

        if (isFillerTask) {
            ejectList.forEach { stringName ->
                getBlockFromName(stringName)?.let {
                    if (swapToBlockOrMove(module, it)) return true
                }
            }
        }

        handleRestock(targetBlock.item)
        return false
    }

    private fun SafeClientEvent.swapToSlotOrMove(slot: Slot) {
        slot.toHotbarSlotOrNull()?.let {
            swapToSlot(it)
        } ?: run {
            val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
            moveToHotbar(module, slot.slotNumber, slotTo)
        }
    }

    private fun SafeClientEvent.sendPlacingPackets(blockPos: BlockPos, side: EnumFacing, hitVecOffset: Vec3d) {
        val isBlackListed = currentBlock in blockBlacklist

        if (isBlackListed) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }

        val placePacket = CPacketPlayerTryUseItemOnBlock(blockPos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
        connection.sendPacket(placePacket)
        player.swingArm(EnumHand.MAIN_HAND)

        if (isBlackListed) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }

        defaultScope.launch {
            delay(threshold * 50L)

            if (state == State.PENDING) {
                state = State.INVALID
                if (dynamicDelay) waitPenalty++
            }
        }
    }

    fun acceptPacketState(packetBlockState: IBlockState) {
        if (state == State.PENDING
            && (targetBlock == packetBlockState.block || isFillerTask)
        ) {
            state = State.ACCEPTED
        }
    }

    private fun SafeClientEvent.alreadyIsFilled() = world.getCollisionBox(blockPos) != null
    private fun SafeClientEvent.alreadyIsSupported() = world.getBlockState(blockPos.up()).block == material

    override fun gatherInfoToString() = "state=$state"

    override fun SafeClientEvent.gatherDebugInfo(): MutableList<Pair<String, String>> {
        val data: MutableList<Pair<String, String>> = mutableListOf()

        data.add(Pair("state", state.name))

        return data
    }
}