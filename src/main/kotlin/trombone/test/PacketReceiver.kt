package trombone.test

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.items.hotbarSlots
import net.minecraft.block.BlockShulkerBox
import net.minecraft.network.Packet
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketOpenWindow
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.network.play.server.SPacketSetSlot
import net.minecraft.network.play.server.SPacketWindowItems
import trombone.Pathfinder.rubberbandTimer
import trombone.Statistics.durabilityUsages
import trombone.test.task.tasks.BreakTask
import trombone.test.task.tasks.PlaceTask
import trombone.test.task.TaskProcessor

object PacketReceiver {
    fun SafeClientEvent.handlePacket(packet: Packet<*>) {
        when (packet) {
            is SPacketBlockChange -> {
                TaskProcessor.tasks[packet.blockPosition]?.let {
                    with(it) {
                        if (currentBlockState != packet.getBlockState()) {
                            when (it) {
                                is PlaceTask -> it.acceptPacketState(packet.getBlockState())
                                is BreakTask -> it.acceptPacketState(packet.getBlockState())
                            }
                        }
                    }
                }
            }
            is SPacketPlayerPosLook -> {
                rubberbandTimer.reset()
            }
            is SPacketOpenWindow -> {
                TaskProcessor.getContainerTask()?.let {
                    if (it !is RestockTask) return

                    with(it) {
                        it.acceptPacketOpen()
                        if (it.state == RestockTask.State.PENDING) {
                            if ((currentBlock is BlockShulkerBox && packet.guiId == "minecraft:shulker_box")
                                || (currentBlock !is BlockShulkerBox && packet.guiId == "minecraft:container")
                            ) {
                                it.state = RestockTask.State.LOADING_ITEMS
                            }
                        }
                    }
                }
            }
            is SPacketWindowItems -> {
                TaskProcessor.getContainerTask()?.let {
                    if (it !is RestockTask) return

                    with(it) {
                        it.acceptPacketLoaded()
                        if (it.state == RestockTask.State.LOADING_ITEMS) {
                            it.state = RestockTask.State.LOADED
                        }
                    }
                }
            }
            is SPacketSetSlot -> {
                val currentStack = player.hotbarSlots[player.inventory.currentItem].stack
                if (packet.slot == player.inventory.currentItem + 36
                    && packet.stack.item == currentStack.item
                    && packet.stack.itemDamage > currentStack.itemDamage
                ) {
                    durabilityUsages += packet.stack.itemDamage - currentStack.itemDamage
                }
            }
            else -> { /* Ignored */ }
        }
    }
}