package trombone

import HighwayTools
import HighwayTools.anonymizeStats
import baritone.api.pathing.goals.GoalNear
import baritone.api.process.IBaritoneProcess
import baritone.api.process.PathingCommand
import baritone.api.process.PathingCommandType
import com.lambda.client.util.math.CoordinateConverter.asString
import trombone.Pathfinder.goal
import trombone.handler.Tasks.lastTask

/**
 * @author Avanatiker
 * @since 26/08/20
 */
object Process : IBaritoneProcess {

    override fun isTemporary(): Boolean {
        return true
    }

    override fun priority(): Double {
        return 2.0
    }

    override fun onLostControl() {}

    override fun displayName0(): String {
        val processName = if (!anonymizeStats) {
            lastTask?.toString()
                ?: goal?.asString()
                ?: "Thinking"
        } else {
            "Running"
        }

        return "Trombone: $processName"
    }

    override fun isActive(): Boolean {
        return HighwayTools.isActive()
    }

    override fun onTick(p0: Boolean, p1: Boolean): PathingCommand {
        return goal?.let {
            PathingCommand(GoalNear(it, 0), PathingCommandType.SET_GOAL_AND_PATH)
        } ?: PathingCommand(null, PathingCommandType.REQUEST_PAUSE)
    }
}