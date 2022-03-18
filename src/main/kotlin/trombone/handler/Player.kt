package trombone.handler

import HighwayTools.fillerMat
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
import net.minecraft.block.Block.getBlockFromName
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.init.Blocks
import net.minecraft.init.Enchantments
import net.minecraft.init.Items
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemBlock
import net.minecraft.util.math.Vec3d
import trombone.Blueprint.isInsideBlueprintBuild
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
            if (mode != Trombone.Mode.TUNNEL
                && storageManagement
                && grindObsidian
                && containerTask.taskState == TaskState.DONE
                && material == Blocks.OBSIDIAN
                && (player.inventorySlots.countBlock(Blocks.OBSIDIAN) <= saveMaterial &&
                    grindCycles == 0)
            ) {
                val cycles = (player.inventorySlots.count { it.stack.isEmpty || InventoryManager.ejectList.contains(it.stack.item.registryName.toString()) } - 1) * 8
                if (cycles > 0) {
                    moveState = MovementState.RESTOCK
                    grindCycles = cycles
                } else {
                    disableError("No free inventory space.")
                }
                return false
            }

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

    private fun SafeClientEvent.findMaterial(blockTask: BlockTask) = when {
        blockTask.isFiller -> {
            if (isInsideBlueprintBuild(blockTask.blockPos) && mode == Trombone.Mode.HIGHWAY) {
                if (player.inventorySlots.countBlock(material) > 0) {
                    material
                } else {
                    restockFallback(blockTask)
                    Blocks.AIR
                }
            } else {
                if (player.inventorySlots.countBlock(fillerMat) > 0) {
                    fillerMat
                } else {
                    val possibleMaterials = InventoryManager.ejectList.filter { stringName ->
                        getBlockFromName(stringName)?.let {
                            player.inventorySlots.countBlock(it) > 0
                        } ?: run {
                            false
                        }
                    }
                    possibleMaterials.firstOrNull()?.let { stringMaterial ->
                        getBlockFromName(stringMaterial) ?: run {
                            disableError("Invalid eject material: $stringMaterial")
                            Blocks.AIR
                        }
                    } ?: run {
                        if (player.inventorySlots.countBlock(material) > 0) {
                            material
                        } else {
                            restockFallback(blockTask)
                            Blocks.AIR
                        }
                    }
                }
            }
        }
        player.inventorySlots.countBlock(blockTask.block) > 0 -> {
            blockTask.block
        }
        else -> {
            restockFallback(blockTask)
            Blocks.AIR
        }
    }

    private fun SafeClientEvent.restockFallback(blockTask: BlockTask) {
        if (blockTask.block == material && storageManagement) {
            handleRestock(material.item)
        } else {
            disableError("No usable material was found in inventory.")
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

    fun SafeClientEvent.moveToInventory(slot: Slot) {
        player.openContainer.getSlots(27..62).firstOrNull {
            (slot.stack.item == it.stack.item && it.stack.count < slot.slotStackLimit - slot.stack.count)
                || it.stack.item == Items.AIR
        }?.let {
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(
                    player.openContainer.windowId,
                    slot.slotIndex,
                    0,
                    ClickType.QUICK_MOVE
                )
            )
            Tasks.isInventoryManaging = true
        } ?: run {
            player.hotbarSlots.firstOrNull {
                InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
            }?.let {
                module.addInventoryTask(
                    PlayerInventoryManager.ClickInfo(
                        player.openContainer.windowId,
                        slot.slotIndex,
                        it.hotbarSlot,
                        ClickType.SWAP
                    )
                )
                Tasks.isInventoryManaging = true
            } ?: run {
                // ToDo: SWAP Item from hotbar to ejectable item in inventory and then swap target slot with hotbar
                disableError("Inventory full.")
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