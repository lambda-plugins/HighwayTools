package trombone.handler

import HighwayTools.grindObsidian
import HighwayTools.keepFreeSlots
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.minDistance
import HighwayTools.preferEnderChests
import HighwayTools.saveEnder
import HighwayTools.saveFood
import HighwayTools.saveMaterial
import HighwayTools.saveTools
import HighwayTools.searchEChest
import com.lambda.client.commons.extension.ceilToInt
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.util.EntityUtils.getDroppedItems
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.items.*
import com.lambda.client.util.math.VectorUtils
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.world.getVisibleSides
import com.lambda.client.util.world.isPlaceable
import com.lambda.client.util.world.isReplaceable
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.inventory.Slot
import net.minecraft.item.Item
import net.minecraft.item.ItemShulkerBox
import net.minecraft.item.ItemStack
import net.minecraft.util.EnumFacing
import net.minecraft.util.NonNullList
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextFormatting
import trombone.blueprint.BlueprintGenerator.isInsideBlueprintBuild
import trombone.IO.disableError
import trombone.Pathfinder.currentBlockPos
import trombone.handler.Inventory.zipInventory
import trombone.task.BlockTask
import trombone.task.TaskState
import kotlin.math.abs

object Container {
    var containerTask = BlockTask(BlockPos.ORIGIN, TaskState.DONE, Blocks.AIR)
    val shulkerOpenTimer = TickTimer(TimeUnit.TICKS)
    var grindCycles = 0

    fun SafeClientEvent.handleRestock(item: Item) {
        if (preferEnderChests && item.block == Blocks.OBSIDIAN) {
            handleEnderChest(item)
        } else {
            // Case 1: item is in a shulker in the inventory
            getShulkerWith(player.inventorySlots, item)?.let { slot ->
                getRemotePos()?.let { pos ->
                    containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, item = item)
                } ?: run {
                    disableError("Can't find possible container position (Case: 1)")
                }
            } ?: run {
                handleEnderChest(item)
            }
        }
    }

    private fun SafeClientEvent.handleEnderChest(item: Item) {
        if (grindObsidian && item.block == Blocks.OBSIDIAN) {
            // Case 2: desired item is Obsidian and grinding E-Chests is allowed

            if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) <= saveEnder) {
                handleRestock(Blocks.ENDER_CHEST.item)
                return
            }

            if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) > saveEnder) {
                if (grindCycles > 0) {
                    getRemotePos()?.let { pos ->
                        containerTask = BlockTask(pos, TaskState.PLACE, Blocks.ENDER_CHEST, item = Blocks.OBSIDIAN.item)
                        containerTask.destroy = true
                        if (grindCycles > 1) containerTask.collect = false
                        containerTask.itemID = Blocks.OBSIDIAN.id
                        grindCycles--
                    } ?: run {
                        disableError("Can't find possible container position (Case: 3)")
                    }
                } else {
                    val freeSlots = player.inventorySlots.count {
                        it.stack.isEmpty
                            || InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
                    }

                    val cycles = (freeSlots - 1 - keepFreeSlots) * 8

                    if (cycles > 0) {
                        grindCycles = cycles
                    } else {
                        zipInventory()
                    }
                }
            }
        } else {
            // Case 3: last hope is the ender chest

            if (!searchEChest) {
                disableError("${insufficientMaterial(item)}\nTo provide sufficient material, grant access to your ender chest. Activate in settings: ${TextFormatting.GRAY}Storage Management > Search Ender Chest")
                return
            }

            dispatchEnderChest(item)
        }

    }

    private fun SafeClientEvent.dispatchEnderChest(item: Item) {
        if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) > saveEnder) {
            getRemotePos()?.let { pos ->
                containerTask = BlockTask(pos, TaskState.PLACE, Blocks.ENDER_CHEST, item = item)
                containerTask.itemID = Blocks.OBSIDIAN.id
            } ?: run {
                disableError("Can't find possible container position (Case: 4)")
            }
        } else {
            getShulkerWith(player.inventorySlots, Blocks.ENDER_CHEST.item)?.let { slot ->
                getRemotePos()?.let { pos ->
                    containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, item = Blocks.ENDER_CHEST.item)
                } ?: run {
                    disableError("Can't find possible container position (Case: 5)")
                }
            } ?: run {
                disableError("No ${Blocks.ENDER_CHEST.localizedName} was found in inventory.")
            }
        }
    }

    private fun SafeClientEvent.getRemotePos(): BlockPos? {
        val origin = currentBlockPos.up().toVec3dCenter()

        return VectorUtils.getBlockPosInSphere(origin, maxReach).asSequence()
            .filter { pos ->
                !isInsideBlueprintBuild(pos)
                    && pos != currentBlockPos
                    && world.isPlaceable(pos)
                    && !world.getBlockState(pos.down()).isReplaceable
                    && world.isAirBlock(pos.up())
                    && getVisibleSides(pos.down()).contains(EnumFacing.UP)
                    && player.positionVector.distanceTo(pos.toVec3dCenter()) > minDistance
                    && pos.y >= currentBlockPos.y
            }.sortedWith(
                compareByDescending<BlockPos> {
                    secureScore(it)
                }.thenBy {
                    it.distanceSqToCenter(origin.x, origin.y, origin.z).ceilToInt()
                }.thenBy {
                    abs(it.y - currentBlockPos.y)
                }
            ).firstOrNull()
    }

    private fun SafeClientEvent.secureScore(pos: BlockPos): Int {
        var safe = 0
        if (!world.getBlockState(pos.down().north()).isReplaceable) safe++
        if (!world.getBlockState(pos.down().east()).isReplaceable) safe++
        if (!world.getBlockState(pos.down().south()).isReplaceable) safe++
        if (!world.getBlockState(pos.down().west()).isReplaceable) safe++
        return safe
    }

    fun getShulkerWith(slots: List<Slot>, item: Item): Slot? {
        return slots.filter {
            it.stack.item is ItemShulkerBox && getShulkerData(it.stack, item) > 0
        }.minByOrNull {
            getShulkerData(it.stack, item)
        }
    }

    private fun getShulkerData(stack: ItemStack, item: Item): Int {
        val tagCompound = if (stack.item is ItemShulkerBox) stack.tagCompound else return 0

        if (tagCompound != null && tagCompound.hasKey("BlockEntityTag", 10)) {
            val blockEntityTag = tagCompound.getCompoundTag("BlockEntityTag")
            if (blockEntityTag.hasKey("Items", 9)) {
                val shulkerInventory = NonNullList.withSize(27, ItemStack.EMPTY)
                ItemStackHelper.loadAllItems(blockEntityTag, shulkerInventory)
                return shulkerInventory.count { it.item == item }
            }
        }

        return 0
    }

    fun SafeClientEvent.getCollectingPosition(): BlockPos? {
        val range = 8f
        getDroppedItems(containerTask.itemID, range = range)
            .minByOrNull { player.getDistance(it) }
            ?.positionVector
            ?.let { itemVec ->
                return VectorUtils.getBlockPosInSphere(itemVec, range).asSequence()
                    .filter { pos ->
                        world.isAirBlock(pos.up())
                            && world.isAirBlock(pos)
                            && !world.isPlaceable(pos.down())
                    }
                    .sortedWith(
                        compareBy<BlockPos> {
                            it.distanceSqToCenter(itemVec.x, itemVec.y, itemVec.z)
                        }.thenBy {
                            it.y
                        }
                    ).firstOrNull()
            }
        return null
    }

    private fun SafeClientEvent.insufficientMaterial(item: Item): String {
        val itemCount = player.inventorySlots.countItem(item)
        var message = ""
        if (saveMaterial > 0 && item == material.item) message += insufficientMaterialPrint(itemCount, saveMaterial, material.localizedName)
        if (saveEnder > 0 && item.block == Blocks.ENDER_CHEST) message += insufficientMaterialPrint(itemCount, saveEnder, Blocks.ENDER_CHEST.localizedName)
        if (saveTools > 0 && item == Items.DIAMOND_PICKAXE) message += insufficientMaterialPrint(itemCount, saveTools, "Diamond Pickaxe(s)")
        if (saveFood > 0 && item == Items.GOLDEN_APPLE) message += insufficientMaterialPrint(itemCount, saveFood, "Golden Apple(s)")
        return "$message\nTo continue anyways, set setting in ${TextFormatting.GRAY}Storage Management > Save <Material>${TextFormatting.RESET} to zero."
    }

    private fun insufficientMaterialPrint(itemCount: Int, settingCount: Int, name: String) =
        "For safety purposes you need ${TextFormatting.AQUA}${settingCount - itemCount + 1}${TextFormatting.RED} more $name in your inventory ($itemCount/${settingCount + 1})."
}