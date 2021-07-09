package trombone

import HighwayTools.bridging
import HighwayTools.maxReach
import HighwayTools.moveSpeed
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
import trombone.handler.Container.containerTask
import trombone.handler.Container.getCollectingPosition
import trombone.handler.Player.lastHitVec
import trombone.handler.Tasks.isTaskDone
import trombone.handler.Tasks.tasks
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

    private fun checkTasks(pos: BlockPos): Boolean {
        return ((containerTask.taskState != TaskState.DONE &&
            pos.toVec3dCenter().distanceTo(containerTask.blockPos.toVec3dCenter()) < maxReach - 0.5) ||
            containerTask.taskState == TaskState.DONE) &&
            tasks.values.all {
            it.taskState == TaskState.DONE ||
                pos.toVec3dCenter().distanceTo(it.blockPos.toVec3dCenter()) < maxReach - 0.5
        }
    }

    fun SafeClientEvent.shouldBridge(): Boolean {
        return bridging &&
            world.isAirBlock(currentBlockPos.add(startingDirection.directionVec)) &&
            world.isAirBlock(currentBlockPos.add(startingDirection.directionVec).up()) &&
            world.getBlockState(currentBlockPos.add(startingDirection.directionVec).down()).isReplaceable &&
            tasks.values.filter {
                it.taskState == TaskState.PLACE ||
                    it.taskState == TaskState.LIQUID
            }.none {
                it.sequence.isNotEmpty()
            } &&
            tasks.values.none {
                it.taskState == TaskState.PENDING_PLACE
            }
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