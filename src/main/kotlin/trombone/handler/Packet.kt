package trombone.handler

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.items.hotbarSlots
import net.minecraft.init.Blocks
import net.minecraft.network.Packet
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketOpenWindow
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.network.play.server.SPacketSetSlot
import net.minecraft.network.play.server.SPacketWindowItems
import trombone.Blueprint.isInsideBlueprint
import trombone.Pathfinder.rubberbandTimer
import trombone.Statistics.durabilityUsages
import trombone.handler.Container.containerTask
import trombone.task.TaskManager.tasks
import trombone.task.TaskState

object Packet {
    fun SafeClientEvent.handlePacket(packet: Packet<*>) {
        when (packet) {
            is SPacketBlockChange -> {
                val pos = packet.blockPosition
                if (!isInsideBlueprint(pos)) return

                val prev = world.getBlockState(pos).block
                val new = packet.getBlockState().block

                if (prev != new) {
                    val task = if (pos == containerTask.blockPos) {
                        containerTask
                    } else {
                        tasks[pos] ?: return
                    }

                    when (task.taskState) {
                        TaskState.PENDING_BREAK, TaskState.BREAKING -> {
                            if (new == Blocks.AIR) {
                                task.updateState(TaskState.BROKEN)
                            }
                        }
                        TaskState.PENDING_PLACE -> {
                            if (task.targetBlock != Blocks.AIR && task.targetBlock == new) {
                                task.updateState(TaskState.PLACED)
                            }
                        }
                        else -> {
                            // Ignored
                        }
                    }
                }
            }
            is SPacketPlayerPosLook -> {
                rubberbandTimer.reset()
            }
            is SPacketOpenWindow -> {
                if (containerTask.taskState != TaskState.DONE &&
                    packet.guiId == "minecraft:shulker_box" && containerTask.isShulker() ||
                    packet.guiId == "minecraft:container" && !containerTask.isShulker()) {
                    containerTask.isOpen = true
                }
            }
            is SPacketWindowItems -> {
                if (containerTask.isOpen) containerTask.isLoaded = true
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