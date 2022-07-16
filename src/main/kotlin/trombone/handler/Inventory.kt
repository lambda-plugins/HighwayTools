package trombone.handler

import HighwayTools.keepFreeSlots
import HighwayTools.material
import HighwayTools.leastMaterial
import HighwayTools.leastTools
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
import net.minecraft.inventory.Container
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemBlock
import net.minecraft.util.math.Vec3d
import trombone.IO.disableError
import trombone.Trombone.module
import trombone.handler.Container.containerTask
import trombone.handler.Container.getShulkerWith
import trombone.handler.Container.handleRestock
import trombone.task.BlockTask
import trombone.task.TaskState
import java.util.concurrent.ConcurrentLinkedDeque

object Inventory {
    var lastHitVec: Vec3d = Vec3d.ZERO
    var waitTicks = 0

    val packetLimiter = ConcurrentLinkedDeque<Long>()

    @Suppress("UNUSED")
    enum class RotationMode {
        OFF, SPOOF
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
        if (blockTask.isShulker()) {
            getShulkerWith(player.inventorySlots, blockTask.item)?.let { slot ->
                blockTask.itemID = slot.stack.item.id
                slot.toHotbarSlotOrNull()?.let {
                    swapToSlot(it)
                } ?: run {
                    val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                    moveToHotbar(module, slot.slotNumber, slotTo)
                }
            }
            return true
        } else {
            val useMat = findMaterial(blockTask)
            if (useMat == Blocks.AIR) return false

            val success = swapToBlockOrMove(module, useMat, predicateSlot = {
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
        return if (blockTask.targetBlock == material) {
            if (player.inventorySlots.countBlock(material) > leastMaterial) {
                material
            } else {
                restockFallback(blockTask)
                Blocks.AIR
            }
        } else {
            if (player.inventorySlots.countBlock(blockTask.targetBlock) > 0) {
                blockTask.targetBlock
            } else {
                val possibleMaterials = mutableSetOf<Block>()
                InventoryManager.ejectList.forEach { stringName ->
                    getBlockFromName(stringName)?.let {
                        if (player.inventorySlots.countBlock(it) > 0) possibleMaterials.add(it)
                    }
                }

                if (possibleMaterials.isEmpty()) {
                    if (player.inventorySlots.countBlock(material) > leastMaterial) {
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
        if (storageManagement) {
            handleRestock(blockTask.targetBlock.item)
        } else {
            disableError("No usable material was found in inventory.")
        }
    }

    fun SafeClientEvent.swapOrMoveBestTool(blockTask: BlockTask): Boolean {
        if (player.inventorySlots.countItem(Items.DIAMOND_PICKAXE) <= leastTools) {
            return if (containerTask.taskState == TaskState.DONE && storageManagement) {
                handleRestock(Items.DIAMOND_PICKAXE)
                false
            } else {
                swapOrMoveTool(blockTask)
            }
        }

        return swapOrMoveTool(blockTask)
    }

    fun SafeClientEvent.zipInventory() {
        val compressibleStacks = player.inventorySlots.filter { comp ->
            comp.stack.count < comp.stack.maxStackSize
                && player.inventorySlots.countByStack { comp.stack.item == it.item } > 1
        }

        if (compressibleStacks.isEmpty()) {
            disableError("Inventory full. (Considering that $keepFreeSlots slots are supposed to stay free)")
            return
        }

        compressibleStacks.forEach { slot ->
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(slot = slot.slotNumber, type = ClickType.QUICK_MOVE)
            )
        }
    }

    private fun SafeClientEvent.swapOrMoveTool(blockTask: BlockTask) =
        getBestTool(blockTask)?.let { slotFrom ->
            blockTask.toolToUse = slotFrom.stack
            slotFrom.toHotbarSlotOrNull()?.let {
                swapToSlot(it)
            } ?: run {
                val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                moveToHotbar(module, slotFrom.slotNumber, slotTo)
            }
            true
        } ?: run {
            false
        }

    fun SafeClientEvent.moveToInventory(originSlot: Slot, container: Container) {
        container.getSlots(27..62).firstOrNull {
            originSlot.stack.item == it.stack.item
                && it.stack.count < originSlot.stack.maxStackSize - originSlot.stack.count
        }?.let { _ ->
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(
                    container.windowId,
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
                        container.windowId,
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
                            container.windowId,
                            0,
                            freeSlot.slotNumber,
                            ClickType.SWAP
                        ),
                        PlayerInventoryManager.ClickInfo(
                            container.windowId,
                            freeSlot.slotNumber,
                            0,
                            ClickType.SWAP
                        )
                    )
                } ?: run {
                    zipInventory()
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