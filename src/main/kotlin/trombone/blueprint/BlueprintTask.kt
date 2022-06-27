package trombone.blueprint

import net.minecraft.block.Block

data class BlueprintTask(val targetBlock: Block, val isFiller: Boolean = false, val isSupport: Boolean = false)
