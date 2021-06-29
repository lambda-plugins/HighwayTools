package trombone

import com.lambda.client.event.SafeClientEvent

object Pathfinder {
    fun update(event: SafeClientEvent) {

    }

    enum class MovementState {
        RUNNING, PICKUP, BRIDGE
    }
}