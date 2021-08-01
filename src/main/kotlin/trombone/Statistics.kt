package trombone

import HighwayTools.anonymizeStats
import HighwayTools.breakDelay
import HighwayTools.dynamicDelay
import HighwayTools.fillerMat
import HighwayTools.height
import HighwayTools.material
import HighwayTools.mode
import HighwayTools.placeDelay
import HighwayTools.printDebug
import HighwayTools.width
import HighwayToolsHud
import HighwayToolsHud.showEnvironment
import HighwayToolsHud.showEstimations
import HighwayToolsHud.showLifeTime
import HighwayToolsHud.showPerformance
import HighwayToolsHud.showSession
import HighwayToolsHud.showTask
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.client.Hud.primaryColor
import com.lambda.client.module.modules.client.Hud.secondaryColor
import com.lambda.client.util.graphics.font.TextComponent
import com.lambda.client.util.items.countBlock
import com.lambda.client.util.items.countItem
import com.lambda.client.util.items.inventorySlots
import com.lambda.client.util.items.item
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.ItemPickaxe
import net.minecraft.network.play.client.CPacketClientStatus
import net.minecraft.stats.StatList
import trombone.IO.disableError
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.moveState
import trombone.Pathfinder.startingBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Trombone.Mode
import trombone.handler.Container.containerTask
import trombone.handler.Container.grindCycles
import trombone.handler.Player.packetLimiter
import trombone.handler.Player.packetLimiterMutex
import trombone.handler.Tasks.sortedTasks
import trombone.interaction.Place.extraPlaceDelay
import trombone.task.BlockTask
import trombone.task.TaskState

object Statistics {
    val simpleMovingAveragePlaces = ArrayDeque<Long>()
    val simpleMovingAverageBreaks = ArrayDeque<Long>()
    val simpleMovingAverageDistance = ArrayDeque<Long>()
    var totalBlocksPlaced = 0
    var totalBlocksBroken = 0
    private var totalDistance = 0.0
    private var runtimeMilliSeconds = 0
    private var prevFood = 0
    private var foodLoss = 1
    private var materialLeft = 0
    private var fillerMatLeft = 0
    private var lastToolDamage = 0
    private var durabilityUsages = 0
    private var matPlaced = 0
    private var enderMined = 0
    private var netherrackMined = 0
    private var pickaxeBroken = 0

    fun SafeClientEvent.updateStats() {
        updateFood()

        /* Update the minecraft statistics all 15 seconds */
        if (runtimeMilliSeconds % 15000 == 0) {
            connection.sendPacket(CPacketClientStatus(CPacketClientStatus.State.REQUEST_STATS))
        }
        runtimeMilliSeconds += 50

        updateDequeues()
    }

    private fun SafeClientEvent.updateFood() {
        val currentFood = player.foodStats.foodLevel
        if (currentFood < 7.0) {
            disableError("Out of food")
        }
        if (currentFood != prevFood) {
            if (currentFood < prevFood) foodLoss++
            prevFood = currentFood
        }
    }

    fun updateTotalDistance() {
        totalDistance += startingBlockPos.distanceTo(currentBlockPos)
    }

    private fun updateDequeues() {
        val removeTime = System.currentTimeMillis() - HighwayToolsHud.simpleMovingAverageRange * 1000L

        updateDeque(simpleMovingAveragePlaces, removeTime)
        updateDeque(simpleMovingAverageBreaks, removeTime)
        updateDeque(simpleMovingAverageDistance, removeTime)

        runBlocking {
            packetLimiterMutex.withLock {
                updateDeque(packetLimiter, System.currentTimeMillis() - 1000L)
            }
        }
    }

    private fun updateDeque(deque: ArrayDeque<Long>, removeTime: Long) {
        while (deque.isNotEmpty() && deque.first() < removeTime) {
            deque.removeFirst()
        }
    }

    fun SafeClientEvent.gatherStatistics(displayText: TextComponent) {
        val runtimeSec = (runtimeMilliSeconds / 1000) + 0.0001
        val distanceDone = startingBlockPos.distanceTo(currentBlockPos).toInt() + totalDistance

        if (showSession) gatherSession(displayText, runtimeSec)

        if (showLifeTime) gatherLifeTime(displayText)

        if (showPerformance) gatherPerformance(displayText, runtimeSec, distanceDone)

        if (showEnvironment) gatherEnvironment(displayText)

        if (showTask) gatherTask(displayText)

        if (showEstimations) gatherEstimations(displayText, runtimeSec, distanceDone)

        if (printDebug) {
            if (containerTask.taskState != TaskState.DONE) {
                displayText.addLine("Container", primaryColor, scale = 0.6f)
                displayText.addLine(containerTask.prettyPrint(), primaryColor, scale = 0.6f)
            }

            if (sortedTasks.isNotEmpty()) {
                displayText.addLine("Pending", primaryColor, scale = 0.6f)
                addTaskComponentList(displayText, sortedTasks)
            }
        }

        displayText.addLine("by Constructor#9948/Avanatiker", primaryColor, scale = 0.6f)
    }

    private fun gatherSession(displayText: TextComponent, runtimeSec: Double) {
        val seconds = (runtimeSec % 60.0).toInt().toString().padStart(2, '0')
        val minutes = ((runtimeSec % 3600.0) / 60.0).toInt().toString().padStart(2, '0')
        val hours = (runtimeSec / 3600.0).toInt().toString().padStart(2, '0')

        displayText.addLine("Session", primaryColor)

        displayText.add("    Runtime:", primaryColor)
        displayText.addLine("$hours:$minutes:$seconds", secondaryColor)

        displayText.add("    Direction:", primaryColor)
        displayText.addLine("${startingDirection.displayName} / ${startingDirection.displayNameXY}", secondaryColor)

        if (!anonymizeStats) displayText.add("    Start:", primaryColor)
        if (!anonymizeStats) displayText.addLine("(${startingBlockPos.asString()})", secondaryColor)

        displayText.add("    Placed / destroyed:", primaryColor)
        displayText.addLine("%,d".format(totalBlocksPlaced) + " / " + "%,d".format(totalBlocksBroken), secondaryColor)


    }

    private fun SafeClientEvent.gatherLifeTime(displayText: TextComponent) {
        matPlaced = StatList.getObjectUseStats(material.item)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0
        enderMined = StatList.getBlockStats(Blocks.ENDER_CHEST)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0
        netherrackMined = StatList.getBlockStats(Blocks.NETHERRACK)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0
        pickaxeBroken = StatList.getObjectBreakStats(Items.DIAMOND_PICKAXE)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0

        if (matPlaced + enderMined + netherrackMined + pickaxeBroken > 0) {
            displayText.addLine("Lifetime", primaryColor)
        }

        if (mode == Mode.HIGHWAY || mode == Mode.FLAT) {
            if (matPlaced > 0) {
                displayText.add("    ${material.localizedName} placed:", primaryColor)
                displayText.addLine("%,d".format(matPlaced), secondaryColor)
            }

            if (enderMined > 0) {
                displayText.add("    ${Blocks.ENDER_CHEST.localizedName} mined:", primaryColor)
                displayText.addLine("%,d".format(enderMined), secondaryColor)
            }
        }

        if (netherrackMined > 0) {
            displayText.add("    ${Blocks.NETHERRACK.localizedName} mined:", primaryColor)
            displayText.addLine("%,d".format(netherrackMined), secondaryColor)
        }

        if (pickaxeBroken > 0) {
            displayText.add("    Diamond Pickaxe broken:", primaryColor)
            displayText.addLine("%,d".format(pickaxeBroken), secondaryColor)
        }
    }

    private fun gatherPerformance(displayText: TextComponent, runtimeSec: Double, distanceDone: Double) {
        displayText.addLine("Performance", primaryColor)

        displayText.add("    Placements / s: ", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format(totalBlocksPlaced / runtimeSec, simpleMovingAveragePlaces.size / HighwayToolsHud.simpleMovingAverageRange.toDouble()), secondaryColor)

        displayText.add("    Breaks / s:", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format(totalBlocksBroken / runtimeSec, simpleMovingAverageBreaks.size / HighwayToolsHud.simpleMovingAverageRange.toDouble()), secondaryColor)

        displayText.add("    Distance km / h:", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format((distanceDone / runtimeSec * 60.0 * 60.0) / 1000.0, (simpleMovingAverageDistance.size / HighwayToolsHud.simpleMovingAverageRange * 60.0 * 60.0) / 1000.0), secondaryColor)

        displayText.add("    Food level loss / h:", primaryColor)
        displayText.addLine("%.2f".format(totalBlocksBroken / foodLoss.toDouble()), secondaryColor)

        displayText.add("    Pickaxes / h:", primaryColor)
        displayText.addLine("%.2f".format((durabilityUsages / runtimeSec) * 60.0 * 60.0 / 1561.0), secondaryColor)
    }

    private fun gatherEnvironment(displayText: TextComponent) {
        displayText.addLine("Environment", primaryColor)

        displayText.add("    Materials:", primaryColor)
        displayText.addLine("Main(${material.localizedName}) Filler(${fillerMat.localizedName})", secondaryColor)

        displayText.add("    Dimensions:", primaryColor)
        displayText.addLine("Width($width) Height($height)", secondaryColor)

        displayText.add("    Delays:", primaryColor)
        if (dynamicDelay) {
            displayText.addLine("Place(${placeDelay + extraPlaceDelay}) Break($breakDelay)", secondaryColor)
        } else {
            displayText.addLine("Place($placeDelay) Break($breakDelay)", secondaryColor)
        }

        displayText.add("    Movement:", primaryColor)
        displayText.addLine("$moveState", secondaryColor)
    }

    private fun gatherTask(displayText: TextComponent) {
        val task: BlockTask? = if (containerTask.taskState != TaskState.DONE) {
            containerTask
        } else {
            sortedTasks.firstOrNull()
        }
        task?.let {
            displayText.addLine("Task", primaryColor)

            displayText.add("    Status:", primaryColor)
            displayText.addLine("${it.taskState}", secondaryColor)

            displayText.add("    Target block:", primaryColor)
            displayText.addLine(it.block.localizedName, secondaryColor)

            if (!anonymizeStats) displayText.add("    Position:", primaryColor)
            if (!anonymizeStats) displayText.addLine("(${it.blockPos.asString()})", secondaryColor)

            displayText.add("    Ticks stuck:", primaryColor)
            displayText.addLine("${it.stuckTicks}", secondaryColor)
        }
    }

    private fun SafeClientEvent.gatherEstimations(displayText: TextComponent, runtimeSec: Double, distanceDone: Double) {
        when (mode) {
            Mode.HIGHWAY, Mode.FLAT -> {
                materialLeft = player.inventorySlots.countBlock(material)
                fillerMatLeft = player.inventorySlots.countBlock(fillerMat)
                val indirectMaterialLeft = 8 * player.inventorySlots.countBlock(Blocks.ENDER_CHEST)

                val pavingLeft = materialLeft / (totalBlocksPlaced.coerceAtLeast(1) / distanceDone.coerceAtLeast(1.0))

                // ToDo: Cache shulker count

//                  val pavingLeftAll = (materialLeft + indirectMaterialLeft) / ((totalBlocksPlaced + 0.001) / (distanceDone + 0.001))

                val secLeft = (pavingLeft).coerceAtLeast(0.0) / (startingBlockPos.distanceTo(currentBlockPos).toInt() / runtimeSec)
                val secondsLeft = (secLeft % 60).toInt().toString().padStart(2, '0')
                val minutesLeft = ((secLeft % 3600) / 60).toInt().toString().padStart(2, '0')
                val hoursLeft = (secLeft / 3600).toInt().toString().padStart(2, '0')

                displayText.addLine("Refill", primaryColor)
                displayText.add("    ${material.localizedName}:", primaryColor)

                if (material == Blocks.OBSIDIAN) {
                    displayText.addLine("Direct($materialLeft) Indirect($indirectMaterialLeft)", secondaryColor)
                } else {
                    displayText.addLine("$materialLeft", secondaryColor)
                }

                displayText.add("    ${fillerMat.localizedName}:", primaryColor)
                displayText.addLine("$fillerMatLeft", secondaryColor)

                if (moveState == Pathfinder.MovementState.RESTOCK) {
                    displayText.add("    Ender Chest cycles:", primaryColor)
                    displayText.addLine("$grindCycles", secondaryColor)
                } else {
                    displayText.add("    Distance left:", primaryColor)
                    displayText.addLine("${pavingLeft.toInt()}", secondaryColor)

                    if (!anonymizeStats) displayText.add("    Destination:", primaryColor)
                    if (!anonymizeStats) displayText.addLine("(${currentBlockPos.add(startingDirection.directionVec.multiply(pavingLeft.toInt())).asString()})", secondaryColor)

                    displayText.add("    ETA:", primaryColor)
                    displayText.addLine("$hoursLeft:$minutesLeft:$secondsLeft", secondaryColor)
                }
            }
            Mode.TUNNEL -> {
                val pickaxesLeft = player.inventorySlots.countItem<ItemPickaxe>()

                val tunnelingLeft = (pickaxesLeft * 1561) / (durabilityUsages.coerceAtLeast(1) / distanceDone.coerceAtLeast(1.0))

                val secLeft = tunnelingLeft.coerceAtLeast(0.0) / (startingBlockPos.distanceTo(currentBlockPos).toInt() / runtimeSec)
                val secondsLeft = (secLeft % 60).toInt().toString().padStart(2, '0')
                val minutesLeft = ((secLeft % 3600) / 60).toInt().toString().padStart(2, '0')
                val hoursLeft = (secLeft / 3600).toInt().toString().padStart(2, '0')

                displayText.addLine("Destination:", primaryColor)

                displayText.add("    Pickaxes:", primaryColor)
                displayText.addLine("$pickaxesLeft", secondaryColor)

                displayText.add("    Distance left:", primaryColor)
                displayText.addLine("${tunnelingLeft.toInt()}", secondaryColor)

                if (!anonymizeStats) displayText.add("    Destination:", primaryColor)
                if (!anonymizeStats) displayText.addLine("(${currentBlockPos.add(startingDirection.directionVec.multiply(tunnelingLeft.toInt())).asString()})", secondaryColor)

                displayText.add("    ETA:", primaryColor)
                displayText.addLine("$hoursLeft:$minutesLeft:$secondsLeft", secondaryColor)
            }
        }
    }

    private fun addTaskComponentList(displayText: TextComponent, tasks: Collection<BlockTask>) {
        tasks.forEach {
            displayText.addLine(it.prettyPrint(), primaryColor, scale = 0.6f)
        }
    }

    fun resetStats() {
        simpleMovingAveragePlaces.clear()
        simpleMovingAverageBreaks.clear()
        simpleMovingAverageDistance.clear()
        totalBlocksPlaced = 0
        totalBlocksBroken = 0
        totalDistance = 0.0
        runtimeMilliSeconds = 0
        prevFood = 0
        foodLoss = 1
        materialLeft = 0
        fillerMatLeft = 0
        lastToolDamage = 0
        durabilityUsages = 0
    }
}
