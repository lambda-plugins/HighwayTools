package trombone

import HighwayTools
import HighwayTools.rubberbandTimeout
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.misc.AutoObsidian
import com.lambda.client.process.PauseProcess
import net.minecraft.world.EnumDifficulty
import trombone.BaritoneHelper.resetBaritone
import trombone.BaritoneHelper.setupBaritone
import trombone.IO.printDisable
import trombone.IO.printEnable
import trombone.Pathfinder.clearProcess
import trombone.Pathfinder.setupPathing
import trombone.Pathfinder.updatePathing
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
        setupPathing()
        setupBaritone()
        printEnable()
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
        updatePathing()
        updateRotation()
    }

    private fun SafeClientEvent.pauseCheck(): Boolean =
        !Pathfinder.rubberbandTimer.tick(rubberbandTimeout.toLong(), false) ||
            player.inventory.isEmpty ||
            PauseProcess.isActive ||
            AutoObsidian.isActive() ||
            (world.difficulty == EnumDifficulty.PEACEFUL &&
                player.dimension == 1 &&
                @Suppress("UNNECESSARY_SAFE_CALL")
                player.serverBrand?.contains("2b2t") == true)
}