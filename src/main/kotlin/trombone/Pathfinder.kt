package trombone

import HighwayTools.maxReach
import HighwayTools.moveSpeed
import HighwayTools.scaffold
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.EntityUtils.flooredPosition
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.items.*
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper.sendBaritoneCommand
import com.lambda.client.util.world.getHitVec
import com.lambda.client.util.world.getHitVecOffset
import com.lambda.client.util.world.isReplaceable
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.IO.disableError
import trombone.Statistics.simpleMovingAverageDistance
import trombone.Trombone.active
import trombone.handler.Container.containerTask
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Player.lastHitVec
import trombone.handler.Player.moveToInventory
import trombone.handler.Tasks.isTaskDone
import trombone.handler.Tasks.tasks
import trombone.handler.Tasks.updateTasks
import trombone.task.TaskState
import java.lang.Thread.sleep

object Pathfinder {
    var goal: BlockPos? = null
    var moveState = MovementState.RUNNING

    val rubberbandTimer = TickTimer(TimeUnit.TICKS)

    var startingDirection = Direction.NORTH
    var currentBlockPos = BlockPos(0, -1, 0)
    var startingBlockPos = BlockPos(0, -1, 0)
    var distancePending = 0
    var stashBlockPos = BlockPos(0, -1, 0)
    var netherPortalPos = mutableListOf(1)
    var cleanStashPos = mutableListOf(1)
    private val chestOpenTimer = TickTimer(TimeUnit.TICKS)
    private var overworldPortalPos = mutableListOf(1)
    private var returnPos = mutableListOf(1)
    private var targetBlockPos = BlockPos(0, -1, 0)

    enum class MovementState {
        RUNNING, PICKUP, BRIDGE, RESTOCK
    }

    fun SafeClientEvent.setupPathing() {
        moveState = MovementState.RUNNING
        startingBlockPos = player.flooredPosition
        currentBlockPos = startingBlockPos
        startingDirection = Direction.fromEntity(player)
    }

    fun SafeClientEvent.updatePathing() {
        when (moveState) {
            MovementState.RUNNING -> {
                goal = currentBlockPos

                val current = currentBlockPos
                val possiblePos = current.add(startingDirection.directionVec)

                if (!isTaskDone(possiblePos) ||
                    !isTaskDone(possiblePos.up()) ||
                    !isTaskDone(possiblePos.down())) return

                if (!checkTasks(possiblePos.up())) return

                // ToDo: Blocked by entities
//                if (!world.checkNoEntityCollision(AxisAlignedBB(possiblePos.down()), null)) {
//                    possiblePos = possiblePos.add(startingDirection.directionVec)
//                }

                if (current != possiblePos && player.positionVector.distanceTo(possiblePos) < 3) {
                    simpleMovingAverageDistance.add(System.currentTimeMillis())
                    lastHitVec = Vec3d.ZERO
                    currentBlockPos = possiblePos
                    updateTasks()
                }

                if (currentBlockPos.distanceTo(targetBlockPos) < 2 ||
                    (distancePending > 0 &&
                        startingBlockPos.add(
                            startingDirection.directionVec.multiply(distancePending)
                        ).distanceTo(currentBlockPos) == 0.0)) {
                    disableError("Reached target destination")
                    return
                }
            }
            MovementState.BRIDGE -> {
                goal = null
                val isAboveAir = world.getBlockState(player.flooredPosition.down()).isReplaceable
                if (isAboveAir) player.movementInput?.sneak = true
                if (shouldBridge()) {
                    val target = currentBlockPos.toVec3dCenter().add(Vec3d(startingDirection.directionVec))
                    moveTo(target)
                } else {
                    if (!isAboveAir) {
                        moveState = MovementState.RUNNING
                    }
                }
            }
            MovementState.PICKUP -> {
                goal = getCollectingPosition()
            }

            MovementState.RESTOCK -> {
                returnPos.clear()
                returnPos = clearCurrentPos()

                sendBaritoneCommand("#goto ${netherPortalPos[0]} ${netherPortalPos[1]} ${netherPortalPos[2]}")
                sendBaritoneCommand("#goto portal")
                sleep(35000)

                overworldPortalPos.clear()
                overworldPortalPos = clearCurrentPos()
                sendBaritoneCommand("#goto ${cleanStashPos[0]} ${cleanStashPos[1]} ${cleanStashPos[2]}")

                doOpenContainer()
                doRestock()

                sendBaritoneCommand("#goto ${overworldPortalPos[0]} ${overworldPortalPos[1]} ${overworldPortalPos[2]}")
                sendBaritoneCommand("#goto portal")
                sendBaritoneCommand("#goto ${returnPos[0]} ${returnPos[1]} ${returnPos[2]}")

                moveState = MovementState.RUNNING
            }
        }
    }

    fun SafeClientEvent.shouldBridge(): Boolean {
        return scaffold
                && world.isAirBlock(currentBlockPos.add(startingDirection.directionVec))
                && world.isAirBlock(currentBlockPos.add(startingDirection.directionVec).up())
                && world.getBlockState(currentBlockPos.add(startingDirection.directionVec).down()).isReplaceable
                && tasks.values.filter {
            it.taskState == TaskState.PLACE ||
                    it.taskState == TaskState.LIQUID
        }.none {
            it.sequence.isNotEmpty()
        }
                && tasks.values.none {
            it.taskState == TaskState.PENDING_PLACE
        }
    }

    fun updateProcess() {
        if (!active) {
            active = true
            BaritoneUtils.primary?.pathingControlManager?.registerProcess(Process)
        }
    }

    fun clearProcess() {
        active = false
        goal = null
    }

    private fun checkTasks(pos: BlockPos): Boolean {
        return ((containerTask.taskState != TaskState.DONE &&
                pos.toVec3dCenter().distanceTo(containerTask.blockPos.toVec3dCenter()) < maxReach - 0.5) ||
                containerTask.taskState == TaskState.DONE) &&
                tasks.values.all {
                    it.taskState == TaskState.DONE ||
                            pos.toVec3dCenter().distanceTo(it.blockPos.toVec3dCenter()) < maxReach - 0.5
                }
    }

    private fun SafeClientEvent.moveTo(target: Vec3d) {
        player.motionX = (target.x - player.posX).coerceIn((-moveSpeed).toDouble(), moveSpeed.toDouble())
        player.motionZ = (target.z - player.posZ).coerceIn((-moveSpeed).toDouble(), moveSpeed.toDouble())
    }

    private fun SafeClientEvent.doOpenContainer() {
        if (chestOpenTimer.tick(20)) {
            val center = stashBlockPos.toVec3dCenter()
            val diff = player.getPositionEyes(1f).subtract(center)
            val normalizedVec = diff.normalize()

            val side = EnumFacing.getFacingFromVector(normalizedVec.x.toFloat(), normalizedVec.y.toFloat(), normalizedVec.z.toFloat())
            val hitVecOffset = getHitVecOffset(side)

            lastHitVec = getHitVec(stashBlockPos, side)
            connection.sendPacket(CPacketPlayerTryUseItemOnBlock(stashBlockPos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat()))
            player.swingArm(EnumHand.MAIN_HAND)
        }
    }

    private fun SafeClientEvent.doRestock() {
        if (mc.currentScreen is GuiContainer && containerTask.isLoaded) {
            val container = player.openContainer

            if (container.getSlots(0..26).all { it.stack.isEmpty }) {
                disableError("No shulkerBoxes left in chest")
            }

            var found = 0
            var itemsFree = 0

            player.inventorySlots.forEach {
                val stack = it.stack

                itemsFree += when {
                    stack.isEmpty -> 1
                    else -> 0
                }
            }

            container.getSlots(0..26).forEach {
                found += 1
                if (found < itemsFree) moveToInventory(it)
            }
            player.closeScreen()
        } else {
            doOpenContainer()
        }
    }

    private fun clearCurrentPos(): MutableList<Int> {
        return currentBlockPos.toString()
            .replace("BlockPos\\{([^}]+)}".toRegex(), "$1")
            .replace("x=".toRegex(), "")
            .replace("y=".toRegex(), "")
            .replace("z=".toRegex(), "")
            .replace(" ".toRegex(), "")
            .split(",")
            .map { it.toInt() }
            .toMutableList()
    }
}