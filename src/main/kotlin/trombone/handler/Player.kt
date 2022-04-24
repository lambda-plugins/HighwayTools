package trombone.handler

import HighwayTools.grindObsidian
import HighwayTools.material
import HighwayTools.mode
import HighwayTools.saveMaterial
import HighwayTools.saveTools
import HighwayTools.storageManagement
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerInventoryManager
import com.lambda.client.manager.managers.PlayerInventoryManager.addInventoryTask
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.util.items.*
import com.lambda.client.util.math.RotationUtils.getRotationTo
import net.minecraft.block.Block
import net.minecraft.block.Block.getBlockFromName
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.init.Blocks
import net.minecraft.init.Enchantments
import net.minecraft.init.Items
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemBlock
import net.minecraft.util.math.Vec3d
import trombone.IO.disableError
import trombone.Pathfinder.MovementState
import trombone.Pathfinder.moveState
import trombone.Trombone
import trombone.Trombone.module
import trombone.handler.Container.containerTask
import trombone.handler.Container.getShulkerWith
import trombone.handler.Container.grindCycles
import trombone.handler.Container.handleRestock
import trombone.task.BlockTask
import trombone.task.TaskState
import java.util.concurrent.ConcurrentLinkedDeque

object Player {
    var lastHitVec: Vec3d = Vec3d.ZERO
    var waitTicks = 0

    val packetLimiter = ConcurrentLinkedDeque<Long>()

    @Suppress("UNUSED")
    enum class RotationMode {
        OFF, SPOOF, VIEW_LOCK
    }

    fun SafeClientEvent.updateRotation() {
        if (lastHitVec == Vec3d.ZERO) return
        val rotation = getRotationTo(lastHitVec)

        module.sendPlayerPacket {
            rotate(rotation)
        }
    }

    private fun SafeClientEvent.getBestTool(blockTask: BlockTask): Slot? {
        return player.inventorySlots.asReversed().maxByOrNull {
            val stack = it.stack
            if (stack.isEmpty) {
                0.0f
            } else {
                var speed = stack.getDestroySpeed(world.getBlockState(blockTask.blockPos))

                if (speed > 1.0f) {
                    val efficiency = EnchantmentHelper.getEnchantmentLevel(Enchantments.EFFICIENCY, stack)
                    if (efficiency > 0) {
                        speed += efficiency * efficiency + 1.0f
                    }
                }

                speed
            }
        }
    }

    fun SafeClientEvent.swapOrMoveBlock(blockTask: BlockTask): Boolean {
        if (blockTask.isShulker) {
            getShulkerWith(player.inventorySlots, blockTask.item)?.let { slot ->
                blockTask.itemID = slot.stack.item.id
                slot.toHotbarSlotOrNull()?.let {
                    swapToSlot(it)
                } ?: run {
                    val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                    moveToHotbar(slot.slotNumber, slotTo)
                }
            }
            return true
        } else {
            val useMat = findMaterial(blockTask)
            if (useMat == Blocks.AIR) return false

            val success = swapToBlockOrMove(useMat, predicateSlot = {
                it.item is ItemBlock
            })

            return if (!success) {
                disableError("Inventory transaction of $useMat failed.")
                false
            } else {
                true
            }
        }
    }

    private fun SafeClientEvent.findMaterial(blockTask: BlockTask): Block {
        return if (blockTask.block == material) {
            if (player.inventorySlots.countBlock(material) > saveMaterial) {
                material
            } else {
                restockFallback(blockTask)
                Blocks.AIR
            }
        } else {
            if (player.inventorySlots.countBlock(blockTask.block) > 0) {
                blockTask.block
            } else {
                val possibleMaterials = mutableSetOf<Block>()
                InventoryManager.ejectList.forEach { stringName ->
                    getBlockFromName(stringName)?.let {
                        if (player.inventorySlots.countBlock(it) > 0) possibleMaterials.add(it)
                    }
                }

                if (possibleMaterials.isEmpty()) {
                    if (player.inventorySlots.countBlock(material) > saveMaterial) {
                        material
                    } else {
                        restockFallback(blockTask)
                        Blocks.AIR
                    }
                } else {
                    possibleMaterials.first()
                }
            }
        }
    }

    private fun SafeClientEvent.restockFallback(blockTask: BlockTask) {
        if (grindObsidian && blockTask.block == Blocks.OBSIDIAN) {
            val cycles = (player.inventorySlots.count {
                it.stack.isEmpty
                    || InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
            } - 1) * 8
            if (cycles > 0) {
                moveState = MovementState.RESTOCK
                grindCycles = cycles
            } else {
                disableError("No free inventory space.")
            }
        } else {
            if (storageManagement) {
                handleRestock(blockTask.block.item)
            } else {
                disableError("No usable material was found in inventory.")
            }
        }
    }

    fun SafeClientEvent.swapOrMoveBestTool(blockTask: BlockTask): Boolean {
        if (player.inventorySlots.countItem(Items.DIAMOND_PICKAXE) <= saveTools) {
            return if (containerTask.taskState == TaskState.DONE && storageManagement) {
                handleRestock(Items.DIAMOND_PICKAXE)
                false
            } else {
                swapOrMoveTool(blockTask)
            }
        }

        return swapOrMoveTool(blockTask)
    }

    private fun SafeClientEvent.swapOrMoveTool(blockTask: BlockTask) =
        getBestTool(blockTask)?.let { slotFrom ->
            blockTask.toolToUse = slotFrom.stack
            slotFrom.toHotbarSlotOrNull()?.let {
                swapToSlot(it)
            } ?: run {
                val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                moveToHotbar(slotFrom.slotNumber, slotTo)
            }
            true
        } ?: run {
            false
        }

    fun SafeClientEvent.moveToInventory(originSlot: Slot) {
        val container = player.openContainer

        container.getSlots(27..62).firstOrNull {
            originSlot.stack.item == it.stack.item
                && it.stack.count < originSlot.stack.maxStackSize - originSlot.stack.count
        }?.let { _ ->
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(
                    player.openContainer.windowId,
                    originSlot.slotNumber,
                    0,
                    ClickType.QUICK_MOVE
                )
            )
        } ?: run {
            container.getSlots(54..62).firstOrNull {
                InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
                    || it.stack.isEmpty
            }?.let { freeHotbarSlot ->
                module.addInventoryTask(
                    PlayerInventoryManager.ClickInfo(
                        player.openContainer.windowId,
                        originSlot.slotNumber,
                        freeHotbarSlot.slotNumber - 54,
                        ClickType.SWAP
                    )
                )
            } ?: run {
                container.getSlots(27..53).firstOrNull {
                    InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
                        || it.stack.isEmpty
                }?.let { freeSlot ->
                    module.addInventoryTask(
                        PlayerInventoryManager.ClickInfo(
                            player.openContainer.windowId,
                            freeSlot.slotNumber,
                            0,
                            ClickType.SWAP
                        )
                    )
                    module.addInventoryTask(
                        PlayerInventoryManager.ClickInfo(
                            player.openContainer.windowId,
                            originSlot.slotNumber,
                            0,
                            ClickType.SWAP
                        )
                    )
                } ?: run {
                    disableError("Inventory full.")
                }
            }
        }
    }

    fun SafeClientEvent.getEjectSlot(): Slot? {
        return player.inventorySlots.firstByStack {
            !it.isEmpty &&
                InventoryManager.ejectList.contains(it.item.registryName.toString())
        }
    }
}