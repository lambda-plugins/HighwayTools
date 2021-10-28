package trombone.handler

import HighwayTools.anonymizeStats
import HighwayTools.debugMessages
import HighwayTools.fillerMat
import HighwayTools.grindObsidian
import HighwayTools.interacting
import HighwayTools.leaveEmptyShulkers
import HighwayTools.material
import HighwayTools.saveMaterial
import HighwayTools.saveTools
import HighwayTools.storageManagement
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerInventoryManager
import com.lambda.client.manager.managers.PlayerInventoryManager.addInventoryTask
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.util.items.*
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.text.MessageSendHelper
import kotlinx.coroutines.sync.Mutex
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.init.Blocks
import net.minecraft.init.Enchantments
import net.minecraft.init.Items
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemBlock
import net.minecraft.util.math.Vec3d
import trombone.Blueprint.isInsideBlueprintBuild
import trombone.IO.DebugMessages
import trombone.IO.disableError
import trombone.Pathfinder.MovementState
import trombone.Pathfinder.moveState
import trombone.Trombone.module
import trombone.handler.Container.containerTask
import trombone.handler.Container.getShulkerWith
import trombone.handler.Container.grindCycles
import trombone.handler.Container.handleRestock
import trombone.task.BlockTask
import trombone.task.TaskState

object Player {
    var lastHitVec: Vec3d = Vec3d.ZERO
    var waitTicks = 0

    val packetLimiterMutex = Mutex()
    val packetLimiter = ArrayDeque<Long>()

    enum class LimitMode {
        FIXED, SERVER
    }

    @Suppress("UNUSED")
    enum class RotationMode {
        OFF, SPOOF, VIEW_LOCK
    }

    fun SafeClientEvent.updateRotation() {
        if (lastHitVec == Vec3d.ZERO) return
        val rotation = getRotationTo(lastHitVec)

        when (interacting) {
            RotationMode.SPOOF -> {
                module.sendPlayerPacket {
                    rotate(rotation)
                }
            }
            RotationMode.VIEW_LOCK -> {
                player.rotationYaw = rotation.x
                player.rotationPitch = rotation.y
            }
            else -> {
                // RotationMode.OFF
            }
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
            if (storageManagement && grindObsidian &&
                containerTask.taskState == TaskState.DONE &&
                material == Blocks.OBSIDIAN &&
                (player.inventorySlots.countBlock(Blocks.OBSIDIAN) <= saveMaterial &&
                    grindCycles == 0)) {
                val cycles = (player.inventorySlots.count { it.stack.isEmpty || InventoryManager.ejectList.contains(it.stack.item.registryName.toString()) } - 1) * 8
                if (cycles > 0) {
                    moveState = MovementState.RESTOCK
                    grindCycles = cycles
                } else {
                    disableError("No free inventory space.")
                }
                return false
            }

            val useBlock = when {
                blockTask.isFiller -> {
                    if (isInsideBlueprintBuild(blockTask.blockPos)) {
                        if (player.inventorySlots.countBlock(material) > 0) {
                            material
                        } else {
                            disableError("No ${blockTask.block.localizedName} was found in inventory. (1)")
                            return false
                        }
                    } else {
                        if (player.inventorySlots.countBlock(fillerMat) > 0) {
                            fillerMat
                        } else {
                            disableError("No ${blockTask.block.localizedName} was found in inventory. (2)")
                            return false
                        }
                    }
                }
                player.inventorySlots.countBlock(blockTask.block) > 0 -> blockTask.block
                else -> {
                    if (blockTask.block == material && storageManagement) {
                        handleRestock(Blocks.OBSIDIAN.item)
                    } else {
                        disableError("No ${blockTask.block.localizedName} was found in inventory. (3)")
                    }
                    return false
                }
            }

            val success = swapToBlockOrMove(useBlock, predicateSlot = {
                it.item is ItemBlock
            })

            return if (!success) {
                disableError("No ${blockTask.block.localizedName} was found in inventory. (4)")
                false
            } else {
                true
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
            (slot.stack.item == it.stack.item && it.stack.count < slot.slotStackLimit - slot.stack.count) ||
                it.stack.item == Items.AIR
        }?.let {
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(player.openContainer.windowId, slot.slotNumber, 0, ClickType.QUICK_MOVE)
            )
        } ?: run {
            player.hotbarSlots.firstOrNull {
                InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
            }?.let {
                module.addInventoryTask(
                    PlayerInventoryManager.ClickInfo(player.openContainer.windowId, slot.slotNumber, it.hotbarSlot, ClickType.SWAP)
                )
            } ?: run {
                // ToDo: SWAP Item from hotbar to ejectable item in inventory and then swap target slot with hotbar
                disableError("Inventory full.")
            }
        }

        if (leaveEmptyShulkers &&
            player.openContainer.getSlots(0..26).all { it.stack.isEmpty || InventoryManager.ejectList.contains(it.stack.item.registryName.toString()) }) {
            if (debugMessages != DebugMessages.OFF) {
                if (!anonymizeStats) {
                    MessageSendHelper.sendChatMessage("${module.chatName} Left empty ${containerTask.block.localizedName}@(${containerTask.blockPos.asString()})")
                } else {
                    MessageSendHelper.sendChatMessage("${module.chatName} Left empty ${containerTask.block.localizedName}")
                }
            }
            containerTask.updateState(TaskState.DONE)
        } else {
            containerTask.updateState(TaskState.BREAK)
        }

        containerTask.isOpen = false
        player.closeScreen()
    }

    fun SafeClientEvent.getEjectSlot(): Slot? {
        return player.inventorySlots.firstByStack {
            !it.isEmpty &&
                InventoryManager.ejectList.contains(it.item.registryName.toString())
        }
    }
}