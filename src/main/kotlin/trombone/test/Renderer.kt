package trombone.test

import HighwayTools.aFilled
import HighwayTools.aOutline
import HighwayTools.filled
import HighwayTools.outline
import HighwayTools.popUp
import HighwayTools.popUpSpeed
import HighwayTools.showCurrentPos
import HighwayTools.thickness
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GeometryMasks
import net.minecraft.init.Blocks
import trombone.Pathfinder
import trombone.test.task.BuildTask
import trombone.test.task.TaskProcessor
import trombone.test.task.tasks.BreakTask
import trombone.test.task.tasks.DoneTask
import trombone.test.task.tasks.PlaceTask
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Renderer {
    private val renderer = ESPRenderer()

    fun renderWorld() {
        renderer.clear()
        renderer.aFilled = if (filled) aFilled else 0
        renderer.aOutline = if (outline) aOutline else 0
        renderer.thickness = thickness
        val currentTime = System.currentTimeMillis()

        if (showCurrentPos) renderer.add(Pathfinder.currentBlockPos, ColorHolder(255, 255, 255))

        TaskProcessor.tasks.values.forEach {
            if (it.targetBlock == Blocks.AIR && it is DoneTask) return@forEach
            if (it.toRemove) {
                addToRenderer(it, currentTime, true)
            } else {
                addToRenderer(it, currentTime)
            }
        }
        renderer.render(false)
    }

    private fun addToRenderer(buildTask: BuildTask, currentTime: Long, reverse: Boolean = false) {
        var aabb = buildTask.aabb

        if (popUp) {
            val age = (currentTime - buildTask.timeStamp).toDouble()
            val ageX = age.coerceAtMost(popUpSpeed * PI / 2) / popUpSpeed

            val sizeFactor = if (reverse) cos(ageX) else sin(ageX)

            aabb = buildTask.aabb.shrink((0.5 - sizeFactor * 0.5))
        }

        renderer.add(aabb, buildTask.color)

        when (buildTask) {
            is BreakTask -> {
                buildTask.breakInfo?.let { breakInfo ->
                    GeometryMasks.FACEMAP[breakInfo.side]?.let { geoSide ->
                        renderer.add(aabb, buildTask.color.multiply(1.0f), geoSide)
                    }
                }
            }
            is PlaceTask -> {
                buildTask.placeInfo?.let { placeInfo ->
                    GeometryMasks.FACEMAP[placeInfo.side]?.let { geoSide ->
                        renderer.add(aabb, buildTask.color.multiply(1.0f), geoSide)
                    }
                }
            }
        }
    }
}