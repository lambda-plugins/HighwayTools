package trombone.handler

import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.preferEnderChests
import HighwayTools.saveEnder
import HighwayTools.saveFood
import HighwayTools.saveMaterial
import HighwayTools.saveTools
import HighwayTools.searchEChest
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.EntityUtils.getDroppedItems
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.items.*
import com.lambda.client.util.math.VectorUtils
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.world.getVisibleSides
import com.lambda.client.util.world.isPlaceable
import com.lambda.client.util.world.isReplaceable
import com.lambda.commons.extension.ceilToInt
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.inventory.Slot
import net.minecraft.item.Item
import net.minecraft.item.ItemShulkerBox
import net.minecraft.item.ItemStack
import net.minecraft.util.EnumFacing
import net.minecraft.util.NonNullList
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextFormatting
import trombone.Blueprint.isInsideBlueprintBuild
import trombone.IO.disableError
import trombone.Pathfinder.currentBlockPos
import trombone.task.BlockTask
import trombone.task.TaskState
import kotlin.math.abs

object Container {
    var containerTask = BlockTask(BlockPos.ORIGIN, TaskState.DONE, Blocks.AIR, Items.AIR)
    val shulkerOpenTimer = TickTimer(TimeUnit.TICKS)
    var grindCycles = 0

    fun SafeClientEvent.handleRestock(item: Item) {
        if (preferEnderChests && item.block == Blocks.OBSIDIAN) {
            handleEnderChest(item)
        } else {
            getShulkerWith(player.inventorySlots, item)?.let { slot ->
                getRemotePos()?.let { pos ->
                    containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, item)
                    containerTask.isShulker = true
                } ?: run {
                    disableError("Can't find possible container position (Case: 1)")
                }
            } ?: run {
                handleEnderChest(item)
            }
        }
    }

    private fun SafeClientEvent.handleEnderChest(item: Item) {
        if (searchEChest) {
            if (item.block == Blocks.OBSIDIAN) {
                if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) <= saveEnder) {
                    getShulkerWith(player.inventorySlots, Blocks.ENDER_CHEST.item)?.let { slot ->
                        getRemotePos()?.let { pos ->
                            containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, Blocks.ENDER_CHEST.item)
                            containerTask.isShulker = true
                        } ?: run {
                            disableError("Can't find possible container position (Case: 2)")
                        }
                    } ?: run {
                        dispatchEnderChest(Blocks.ENDER_CHEST.item)
                    }
                } else {
                    getRemotePos()?.let { pos ->
                        containerTask = BlockTask(pos, TaskState.PLACE, Blocks.ENDER_CHEST, Blocks.OBSIDIAN.item)
                        containerTask.destroy = true
                        if (grindCycles > 1) containerTask.collect = false
                        containerTask.itemID = Blocks.OBSIDIAN.id
                        grindCycles--
                    } ?: run {
                        disableError("Can't find possible container position (Case: 3)")
                    }
                }
            } else {
                dispatchEnderChest(item)
            }
        } else {
            disableError("${insufficientMaterial(item)}\nTo solve insufficient material grant access to Ender Chest. Activate in Settings ${TextFormatting.GRAY}Storage Management > Search Ender Chest")
        }
    }

    private fun SafeClientEvent.dispatchEnderChest(item: Item) {
        if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) > 0) {
            getRemotePos()?.let { pos ->
                containerTask = BlockTask(pos, TaskState.PLACE, Blocks.ENDER_CHEST, item)
                containerTask.itemID = Blocks.OBSIDIAN.id
            } ?: run {
                disableError("Can't find possible container position (Case: 4)")
            }
        } else {
            getShulkerWith(player.inventorySlots, Blocks.ENDER_CHEST.item)?.let { slot ->
                getRemotePos()?.let { pos ->
                    containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, Blocks.ENDER_CHEST.item)
                    containerTask.isShulker = true
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
                    && player.positionVector.distanceTo(pos.toVec3dCenter()) > 1.1
            }.sortedWith(
                compareByDescending<BlockPos> {
                    safeValue(it)
                }.thenBy {
                    it.distanceSqToCenter(origin.x, origin.y, origin.z).ceilToInt()
                }.thenBy {
                    abs(it.y - currentBlockPos.y)
                }
            ).firstOrNull()
    }

    private fun SafeClientEvent.safeValue(pos: BlockPos): Int {
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
                        world.isAirBlock(pos.up()) &&
                            world.isAirBlock(pos) &&
                            !world.isPlaceable(pos.down())
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
        if (saveFood > 0 && item == Items.GOLDEN_APPLE ) message += insufficientMaterialPrint(itemCount, saveFood, "Golden Apple(s)")
        return "$message\nTo continue anyways, set setting in ${TextFormatting.GRAY}Storage Management > Save <Material>${TextFormatting.RESET} to zero."
    }

    private fun insufficientMaterialPrint(itemCount: Int, settingCount: Int, name: String) =
        "For safety purposes you need ${TextFormatting.AQUA}${settingCount - itemCount + 1}${TextFormatting.RED} more $name in your inventory ($itemCount/${settingCount + 1})."
}