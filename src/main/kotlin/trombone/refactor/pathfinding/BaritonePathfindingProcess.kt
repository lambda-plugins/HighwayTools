package trombone.refactor.pathfinding

import baritone.api.process.IBaritoneProcess

object BaritonePathfindingProcess : IBaritoneProcess {
    override fun isTemporary() = true

    override fun priority() = 2.0

    override fun onLostControl() {
        Navigator.onLostControl()
    }

    override fun displayName0() = Navigator.processInfo()

    override fun isActive() = HighwayTools.isActive()

    override fun onTick(p0: Boolean, p1: Boolean) = Navigator.currentPathingCommand
}