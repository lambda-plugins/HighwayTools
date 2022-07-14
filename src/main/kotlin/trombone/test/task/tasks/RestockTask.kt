package trombone.test.task.tasks

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import net.minecraft.block.Block
import net.minecraft.block.BlockShulkerBox
import net.minecraft.network.play.server.SPacketOpenWindow
import net.minecraft.network.play.server.SPacketWindowItems
import net.minecraft.util.math.BlockPos
import trombone.test.task.BuildTask
import trombone.test.task.TaskProcessor.convertTo

class RestockTask(
    blockPos: BlockPos,
    targetBlock: Block
) : BuildTask(blockPos, targetBlock, false, true, false) {
    var state = State.INIT

    override var priority = 3 + state.prioOffset
    override val timeout = 20
    override var threshold = 20
    override val color = state.colorHolder

    enum class State(val colorHolder: ColorHolder, val prioOffset: Int) {
        INIT(ColorHolder(252, 3, 207 ), 0),
        OPEN_CONTAINER(ColorHolder(252, 3, 207), 0),
        PENDING_OPEN(ColorHolder(252, 3, 207), 0),
        PENDING_ITEM_LIST(ColorHolder(252, 3, 207), 0),
        MOVE_ITEMS(ColorHolder(252, 3, 207), 0),
        CLOSE(ColorHolder(252, 3, 207), 0)
    }

    override fun SafeClientEvent.isValid(): Boolean {
        TODO("Not yet implemented")
    }

    override fun SafeClientEvent.update(): Boolean {
        TODO("Not yet implemented")
    }

    override fun SafeClientEvent.execute() {
        when (state) {
            State.INIT -> {
                state = State.OPEN_CONTAINER
                execute()
            }
            State.OPEN_CONTAINER -> {
                state = State.PENDING_OPEN
            }
            State.PENDING_OPEN -> {
                /* Wait */
            }
            State.PENDING_ITEM_LIST -> {
                /* Wait */
            }
            State.MOVE_ITEMS -> {

            }
            State.CLOSE -> {
                player.closeScreen()

                convertTo<DoneTask>()
            }
        }
    }

    fun acceptPacketOpen(packet: SPacketOpenWindow) {
        if (state == State.PENDING_OPEN) {
            when {
                targetBlock is BlockShulkerBox && packet.guiId == "minecraft:shulker_box" -> state = State.PENDING_ITEM_LIST
                targetBlock !is BlockShulkerBox && packet.guiId == "minecraft:container" -> state = State.PENDING_ITEM_LIST
            }
        }
    }

    fun acceptPacketLoaded() {
        if (state == State.PENDING_ITEM_LIST) {
            state = State.MOVE_ITEMS
        }
    }

    override fun gatherInfoToString() = "state=$state"

    override fun SafeClientEvent.gatherDebugInfo(): MutableList<Pair<String, String>> {
        val data: MutableList<Pair<String, String>> = mutableListOf()

        data.add(Pair("state", state.name))

        return data
    }
}