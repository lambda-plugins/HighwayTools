package trombone.test

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.items.hotbarSlots
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
import trombone.test.task.tasks.RestockTask

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
                TaskProcessor.getContainerTasks().filterIsInstance<RestockTask>().forEach {
                    with(it) {
                        acceptPacketOpen(packet)
                    }
                }
            }
            is SPacketWindowItems -> {
                TaskProcessor.getContainerTasks().filterIsInstance<RestockTask>().forEach {
                    with(it) {
                        acceptPacketLoaded()
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