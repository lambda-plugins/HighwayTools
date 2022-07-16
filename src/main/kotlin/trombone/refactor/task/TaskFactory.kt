package trombone.refactor.task

import com.lambda.client.event.SafeClientEvent
import net.minecraft.block.Block
import net.minecraft.util.math.BlockPos

object TaskFactory {
    enum class StructurePreset {
        HIGHWAY, TUNNEL, FLAT, STEALTH_TUNNEL
    }

    fun SafeClientEvent.populateTasks() {

    }

    fun isInsideBlueprint(blockPos: BlockPos) = true

    fun isInsideBlueprintBuilding(blockPos: BlockPos) = true

    data class BlueprintTask(val targetBlock: Block, val isFiller: Boolean = false, val isSupport: Boolean = false)
}