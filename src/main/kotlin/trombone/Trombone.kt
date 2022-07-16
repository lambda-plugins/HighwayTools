package trombone

import HighwayTools
import HighwayTools.info
import com.lambda.client.event.SafeClientEvent
import trombone.refactor.pathfinding.BaritoneHelper.resetBaritone
import trombone.refactor.pathfinding.BaritoneHelper.setupBaritone
import trombone.IO.pauseCheck
import trombone.IO.printDisable
import trombone.IO.printEnable
import trombone.Pathfinder.clearProcess
import trombone.Pathfinder.setupPathing
import trombone.Pathfinder.updateProcess
import trombone.Statistics.updateStats
import trombone.Statistics.updateTotalDistance
import trombone.handler.Inventory.updateRotation
import trombone.task.TaskManager.clearTasks
import trombone.task.TaskManager.populateTasks
import trombone.task.TaskManager.runTasks

object Trombone {
    val module = HighwayTools
    var active = false

    enum class Structure {
        HIGHWAY, FLAT, TUNNEL
    }

    fun SafeClientEvent.onEnable() {
        clearTasks()
        setupPathing()
        setupBaritone()
        if (info) printEnable()
    }

    fun onDisable() {
        resetBaritone()
        printDisable()
        clearProcess()
        clearTasks()
        updateTotalDistance()
    }

    fun SafeClientEvent.tick() {
        populateTasks()
        updateStats()

        if (pauseCheck()) return

        updateProcess()
        runTasks()
        updateRotation()
    }
}