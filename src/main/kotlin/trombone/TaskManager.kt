package trombone

import com.lambda.client.event.SafeClientEvent
import net.minecraft.util.math.BlockPos
import trombone.task.BlockTask

object TaskManager {
    private val tasks = LinkedHashMap<BlockPos, BlockTask>()

    fun update(event: SafeClientEvent) {

    }

    fun clear() {
        
    }

    fun load(blueprint: Blueprint) {

    }
}