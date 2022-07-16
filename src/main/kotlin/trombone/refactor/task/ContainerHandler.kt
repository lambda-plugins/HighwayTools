package trombone.refactor.task

import HighwayTools.storageManagement
import com.lambda.client.event.SafeClientEvent
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.inventory.Slot
import net.minecraft.item.Item
import net.minecraft.item.ItemShulkerBox
import net.minecraft.item.ItemStack
import net.minecraft.util.NonNullList
import trombone.refactor.pathfinding.Navigator.changeStrategy
import trombone.refactor.pathfinding.strategies.RestockStrategy
import trombone.refactor.task.tasks.DoneTask

object ContainerHandler {
    inline fun <reified T: Item> SafeClientEvent.handleRestock() {
        if (TaskProcessor.getContainerTasks().all { it is DoneTask }
            && storageManagement
        ) {

        }
    }

    fun SafeClientEvent.handleRestock(item: Item) {
        changeStrategy<RestockStrategy>()
    }

    fun getShulkerWith(slots: List<Slot>, item: Item) =
        slots.filter {
            it.stack.item is ItemShulkerBox && getShulkerData(it.stack, item) > 0
        }.minByOrNull {
            getShulkerData(it.stack, item)
        }

    private fun getShulkerData(stack: ItemStack, item: Item): Int {
        if (stack.item !is ItemShulkerBox) return 0

        stack.tagCompound?.let { tagCompound ->
            if (tagCompound.hasKey("BlockEntityTag", 10)) {
                val blockEntityTag = tagCompound.getCompoundTag("BlockEntityTag")

                if (blockEntityTag.hasKey("Items", 9)) {
                    val shulkerInventory = NonNullList.withSize(27, ItemStack.EMPTY)
                    ItemStackHelper.loadAllItems(blockEntityTag, shulkerInventory)
                    return shulkerInventory.count { it.item == item }
                }
            }
        }

        return 0
    }
}