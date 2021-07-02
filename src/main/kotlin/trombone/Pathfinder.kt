package trombone

import HighwayTools.bridging
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
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.IO.disableError
import trombone.Statistics.simpleMovingAverageDistance
import trombone.Trombone.active
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Tasks.checkTasks
import trombone.handler.Tasks.isTaskDone
import trombone.handler.Tasks.sortedTasks
import trombone.handler.Tasks.updateTasks
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

    fun SafeClientEvent.setupPathing() {
        startingBlockPos = player.flooredPosition
        currentBlockPos = startingBlockPos
        startingDirection = Direction.fromEntity(player)
    }

    fun SafeClientEvent.updatePathing() {
        when (moveState) {
            MovementState.RUNNING, MovementState.BRIDGE -> {
                if (!shouldBridge() && moveState == MovementState.BRIDGE) moveState = MovementState.RUNNING

                if (moveState == MovementState.BRIDGE &&
                    bridging && player.positionVector.distanceTo(currentBlockPos) < 1) {
                    val factor = if (startingDirection.isDiagonal) {
                        0.555
                    } else {
                        0.505
                    }

                    val target = currentBlockPos.toVec3dCenter().add(Vec3d(startingDirection.directionVec).scale(factor))

                    player.motionX = (target.x - player.posX).coerceIn(-0.2, 0.2)
                    player.motionZ = (target.z - player.posZ).coerceIn(-0.2, 0.2)
                }

                var nextPos = currentBlockPos
                val possiblePos = nextPos.add(startingDirection.directionVec)

                if (!isTaskDone(possiblePos) ||
                    !isTaskDone(possiblePos.up()) ||
                    !isTaskDone(possiblePos.down())) return

                if (checkTasks(possiblePos.up())) {
                    nextPos = if (world.getBlockState(possiblePos.down()).isReplaceable) {
                        possiblePos.add(startingDirection.directionVec)
                    } else {
                        possiblePos
                    }
                }

                if (currentBlockPos != nextPos && player.positionVector.distanceTo(nextPos) < 3) {
                    simpleMovingAverageDistance.add(System.currentTimeMillis())
                    currentBlockPos = nextPos
                    updateTasks()
                }

                goal = currentBlockPos

                if (currentBlockPos.distanceTo(targetBlockPos) < 2 ||
                    (distancePending > 0 && startingBlockPos.add(startingDirection.directionVec.multiply(distancePending)).distanceTo(currentBlockPos) == 0.0)) {
                    disableError("Reached target destination")
                    return
                }
            }
            MovementState.PICKUP -> {
                goal = getCollectingPosition()
            }
        }
    }

    fun SafeClientEvent.shouldBridge(): Boolean {
        return world.getBlockState(currentBlockPos.add(startingDirection.directionVec).down()).isReplaceable &&
            !sortedTasks.any {
                it.sequence.isNotEmpty() &&
                    (it.taskState == TaskState.PLACE ||
                        it.taskState == TaskState.LIQUID)
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

    enum class MovementState {
        RUNNING, PICKUP, BRIDGE
    }
}