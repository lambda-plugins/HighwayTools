package trombone

import HighwayTools
import com.lambda.client.event.SafeClientEvent
import trombone.BaritoneHelper.resetBaritone
import trombone.BaritoneHelper.setupBaritone
import trombone.IO.pauseCheck
import trombone.IO.printDisable
import trombone.IO.printEnable
import trombone.Pathfinder.clearProcess
import trombone.Pathfinder.setupPathing
import trombone.Pathfinder.updateProcess
import trombone.Renderer.updateRenderer
import trombone.Statistics.updateStats
import trombone.Statistics.updateTotalDistance
import trombone.handler.Player.updateRotation
import trombone.handler.Tasks.clearTasks
import trombone.handler.Tasks.runTasks
import trombone.handler.Tasks.updateTasks

object Trombone {
    val module = HighwayTools
    var active = false

    enum class Mode {
        HIGHWAY, FLAT, TUNNEL
    }

    fun SafeClientEvent.onEnable() {
        clearTasks()
        setupPathing()
        setupBaritone()
        printEnable()
        updateTasks()
    }

    fun onDisable() {
        resetBaritone()
        printDisable()
        clearProcess()
        clearTasks()
        updateTotalDistance()
    }

    fun SafeClientEvent.tick() {
        updateRenderer()
        updateTasks()
        updateStats()

        if (pauseCheck()) return

        updateProcess()
        runTasks()
        updateRotation()
    }
}