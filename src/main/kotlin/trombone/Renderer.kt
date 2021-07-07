package trombone

import HighwayTools.aFilled
import HighwayTools.aOutline
import HighwayTools.anonymizeStats
import HighwayTools.filled
import HighwayTools.outline
import HighwayTools.popUp
import HighwayTools.popUpSpeed
import HighwayTools.showCurrentPos
import HighwayTools.showDebugRender
import HighwayTools.textScale
import HighwayTools.thickness
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.ProjectionUtils
import com.lambda.client.util.graphics.font.FontRenderAdapter
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import org.lwjgl.opengl.GL11
import trombone.Pathfinder.currentBlockPos
import trombone.handler.Container.containerTask
import trombone.handler.Tasks.tasks
import trombone.task.BlockTask
import trombone.task.TaskState
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

        if (showCurrentPos) renderer.add(currentBlockPos, ColorHolder(255, 255, 255))

        if (containerTask.taskState != TaskState.DONE) {
            addToRenderer(containerTask, currentTime)
        }

        tasks.values.forEach {
            if (it.block == Blocks.AIR && it.taskState == TaskState.DONE) return@forEach
            if (it.toRemove) {
                addToRenderer(it, currentTime, true)
            } else {
                addToRenderer(it, currentTime)
            }
        }
        renderer.render(false)
    }

    fun renderOverlay() {
        if (!showDebugRender) return
        GlStateUtils.rescaleActual()

        if (containerTask.taskState != TaskState.DONE) {
            updateOverlay(containerTask.blockPos, containerTask)
        }

        tasks.forEach { (pos, blockTask) ->
            if (blockTask.taskState == TaskState.DONE) return@forEach
            updateOverlay(pos, blockTask)
        }
    }

    fun SafeClientEvent.updateRenderer() {
        containerTask.aabb = world
            .getBlockState(containerTask.blockPos)
            .getSelectedBoundingBox(world, containerTask.blockPos)

        tasks.values.forEach {
            it.aabb = world
                .getBlockState(it.blockPos)
                .getSelectedBoundingBox(world, it.blockPos)
        }
    }

    private fun updateOverlay(pos: BlockPos, blockTask: BlockTask) {
        GL11.glPushMatrix()
        val screenPos = ProjectionUtils.toScreenPos(pos.toVec3dCenter())
        GL11.glTranslated(screenPos.x, screenPos.y, 0.0)
        GL11.glScalef(textScale * 2.0f, textScale * 2.0f, 1.0f)

        val color = ColorHolder(255, 255, 255, 255)

        val debugInfos = mutableListOf<Pair<String, String>>()
        if (!anonymizeStats) debugInfos.add(Pair("Pos", pos.asString()))
        if (blockTask != containerTask) {
            debugInfos.add(Pair("Start Distance", "%.2f".format(blockTask.startDistance)))
            debugInfos.add(Pair("Eye Distance", "%.2f".format(blockTask.eyeDistance)))
        } else {
            debugInfos.add(Pair("Item", "${blockTask.item.registryName}"))
        }
        if (blockTask.taskState == TaskState.PLACE ||
            blockTask.taskState == TaskState.LIQUID) {
            debugInfos.add(Pair("Depth", "${blockTask.sequence.size}"))
            if (blockTask.isLiquidSource) debugInfos.add(Pair("Liquid Source", ""))
        }
        if (blockTask.isOpen) debugInfos.add(Pair("Open", ""))
        if (blockTask.isLoaded) debugInfos.add(Pair("Loaded", ""))
        if (blockTask.destroy) debugInfos.add(Pair("Destroy", ""))
        if (blockTask.stuckTicks > 0) debugInfos.add(Pair("Stuck", "${blockTask.stuckTicks}"))

        debugInfos.forEachIndexed { index, pair ->
            val text = if (pair.second == "") {
                pair.first
            } else {
                "${pair.first}: ${pair.second}"
            }
            val halfWidth = FontRenderAdapter.getStringWidth(text) / -2.0f
            FontRenderAdapter.drawString(text, halfWidth, (FontRenderAdapter.getFontHeight() + 2.0f) * index, color = color)
        }

        GL11.glPopMatrix()
    }

    private fun addToRenderer(blockTask: BlockTask, currentTime: Long, reverse: Boolean = false) {
        if (popUp) {
            val flip = if (reverse) {
                cos(((currentTime - blockTask.timestamp).toDouble()
                    .coerceAtMost(popUpSpeed * PI / 2) / popUpSpeed))
            } else {
                sin(((currentTime - blockTask.timestamp).toDouble()
                    .coerceAtMost(popUpSpeed * PI / 2) / popUpSpeed))
            }
            renderer.add(blockTask.aabb
                .shrink((0.5 - flip * 0.5)),
                blockTask.taskState.color
            )
        } else {
            renderer.add(blockTask.aabb, blockTask.taskState.color)
        }
    }
}