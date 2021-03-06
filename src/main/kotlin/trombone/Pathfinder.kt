package trombone

import HighwayTools.moveSpeed
import HighwayTools.scaffold
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.EntityUtils.flooredPosition
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.world.isReplaceable
import net.minecraft.block.BlockLiquid
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.IO.disableError
import trombone.Statistics.simpleMovingAverageDistance
import trombone.Trombone.active
import trombone.handler.Container.containerTask
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Inventory.lastHitVec
import trombone.task.TaskManager.isBehindPos
import trombone.task.TaskManager.populateTasks
import trombone.task.TaskManager.tasks
import trombone.task.TaskState

object Pathfinder {
    var goal: BlockPos? = null
    var moveState = MovementState.RUNNING

    val rubberbandTimer = TickTimer(TimeUnit.TICKS)

    var startingDirection = Direction.NORTH
    var currentBlockPos = BlockPos(0, -1, 0)
    var startingBlockPos = BlockPos(0, -1, 0)
    private var targetBlockPos = BlockPos(0, -1, 0)
    var distancePending = 0

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

                // ToDo: Rewrite
                if (currentBlockPos.distanceTo(targetBlockPos) < 2 ||
                    (distancePending > 0 &&
                        startingBlockPos.add(
                            startingDirection.directionVec.multiply(distancePending)
                        ).distanceTo(currentBlockPos) == 0.0)) {
                    disableError("Reached target destination")
                    return
                }

                val possiblePos = currentBlockPos.add(startingDirection.directionVec)

                if (!isTaskDone(possiblePos.up())
                    || !isTaskDone(possiblePos)
                    || !isTaskDone(possiblePos.down())
                ) return

                if (!checkForResidue(possiblePos.up())) return

                if (world.getBlockState(possiblePos.down()).isReplaceable) return

                if (currentBlockPos != possiblePos
                    && player.positionVector.distanceTo(currentBlockPos.toVec3dCenter()) < 2
                ) {
                    simpleMovingAverageDistance.add(System.currentTimeMillis())
                    lastHitVec = Vec3d.ZERO
                    currentBlockPos = possiblePos
                    populateTasks()
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
                val target = currentBlockPos.toVec3dCenter()
                if (player.positionVector.distanceTo(target) < 2) {
                    goal = null
                    moveTo(target)
                } else {
                    goal = currentBlockPos
                }
            }
        }
    }

    private fun checkForResidue(pos: BlockPos) =
        containerTask.taskState == TaskState.DONE
            && tasks.values.all {
                it.taskState == TaskState.DONE || !isBehindPos(pos, it.blockPos)
            }

    private fun SafeClientEvent.isTaskDone(pos: BlockPos): Boolean {
        val block = world.getBlockState(pos).block
        return tasks[pos]?.let {
            it.taskState == TaskState.DONE
                && block != Blocks.PORTAL
                && block != Blocks.END_PORTAL
                && block !is BlockLiquid
        } ?: false
    }

    fun SafeClientEvent.shouldBridge(): Boolean {
        return scaffold
            && containerTask.taskState == TaskState.DONE
            && world.isAirBlock(currentBlockPos.add(startingDirection.directionVec))
            && world.isAirBlock(currentBlockPos.add(startingDirection.directionVec).up())
            && world.getBlockState(currentBlockPos.add(startingDirection.directionVec).down()).isReplaceable
            && tasks.values.none { it.taskState == TaskState.PENDING_PLACE }
            && tasks.values.filter {
                it.taskState == TaskState.PLACE
                    || it.taskState == TaskState.LIQUID
            }.none { it.sequence.isNotEmpty() }
    }

    private fun SafeClientEvent.moveTo(target: Vec3d) {
        player.motionX = (target.x - player.posX).coerceIn((-moveSpeed).toDouble(), moveSpeed.toDouble())
        player.motionZ = (target.z - player.posZ).coerceIn((-moveSpeed).toDouble(), moveSpeed.toDouble())
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
}