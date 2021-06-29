package trombone

import com.lambda.client.event.SafeClientEvent
import net.minecraft.network.Packet

object Trombone {
    fun build(blueprint: Blueprint) {
        TaskManager.load(blueprint)
    }

    fun tick(event: SafeClientEvent) {
        TaskManager.update(event)
        Renderer.update()
        Statistics.update(event) // food level etc
        Pathfinder.update(event)
    }

    fun packet(packet: Packet<*>) {

    }

    fun render() {
        Renderer.render()
    }

    fun clear() {
        TaskManager.clear()
    }
}