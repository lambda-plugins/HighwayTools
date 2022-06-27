package trombone.blueprint

import HighwayTools.backfill
import HighwayTools.cleanCorner
import HighwayTools.cleanFloor
import HighwayTools.cleanLeftWall
import HighwayTools.cleanRightWall
import HighwayTools.cleanRoof
import HighwayTools.clearSpace
import HighwayTools.cornerBlock
import HighwayTools.fillerMat
import HighwayTools.height
import HighwayTools.material
import HighwayTools.maxReach
import HighwayTools.mode
import HighwayTools.railing
import HighwayTools.railingHeight
import HighwayTools.width
import com.lambda.client.commons.extension.ceilToInt
import com.lambda.client.commons.extension.floorToInt
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.startingBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Trombone.Structure

object BlueprintGenerator {
    val blueprint = HashMap<BlockPos, BlueprintTask>()

    fun generateBluePrint() {
        blueprint.clear()
        val basePos = currentBlockPos.down()

        if (mode == Structure.FLAT) {
            generateFlat(basePos)
            return
        }

        val zDirection = startingDirection
        val xDirection = zDirection.clockwise(if (zDirection.isDiagonal) 1 else 2)

        for (x in -maxReach.floorToInt() * 5..maxReach.ceilToInt() * 5) {
            val thisPos = basePos.add(zDirection.directionVec.multiply(x))
            if (clearSpace) generateClear(thisPos, xDirection)
            if (mode == Structure.TUNNEL) {
                if (backfill) {
                    generateBackfill(thisPos, xDirection)
                } else {
                    if (cleanFloor) generateFloor(thisPos, xDirection)
                    if (cleanRightWall || cleanLeftWall) generateWalls(thisPos, xDirection)
                    if (cleanRoof) generateRoof(thisPos, xDirection)
                    if (cleanCorner && !cornerBlock && width > 2) generateCorner(thisPos, xDirection)
                }
            } else {
                generateBase(thisPos, xDirection)
            }
        }

        if (mode == Structure.TUNNEL && (!cleanFloor || backfill)) {
            if (startingDirection.isDiagonal) {
                for (x in 0..maxReach.floorToInt()) {
                    val pos = basePos.add(zDirection.directionVec.multiply(x))
                    blueprint[pos] = BlueprintTask(fillerMat, isFiller = true)
                    blueprint[pos.add(startingDirection.clockwise(7).directionVec)] = BlueprintTask(fillerMat, isFiller = true)
                }
            } else {
                for (x in 0..maxReach.floorToInt()) {
                    blueprint[basePos.add(zDirection.directionVec.multiply(x))] = BlueprintTask(fillerMat, isFiller = true)
                }
            }
        }
    }

    private fun generateClear(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            for (h in 0 until height) {
                val x = w - width / 2
                val pos = basePos.add(xDirection.directionVec.multiply(x)).up(h)

                if (mode == Structure.HIGHWAY && h == 0 && isRail(w)) {
                    continue
                }

                if (mode == Structure.HIGHWAY) {
                    blueprint[pos] = BlueprintTask(Blocks.AIR)
                } else {
                    if (!(isRail(w) && h == 0 && !cornerBlock && width > 2)) blueprint[pos.up()] = BlueprintTask(Blocks.AIR)
                }
            }
        }
    }

    private fun generateBase(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            val x = w - width / 2
            val pos = basePos.add(xDirection.directionVec.multiply(x))

            if (mode == Structure.HIGHWAY && isRail(w)) {
                if (!cornerBlock && width > 2 && startingDirection.isDiagonal) blueprint[pos] = BlueprintTask(fillerMat, isSupport = true)
                val startHeight = if (cornerBlock && width > 2) 0 else 1
                for (y in startHeight..railingHeight) {
                    blueprint[pos.up(y)] = BlueprintTask(material)
                }
            } else {
                blueprint[pos] = BlueprintTask(material)
            }
        }
    }

    private fun generateFloor(basePos: BlockPos, xDirection: Direction) {
        val wid = if (cornerBlock && width > 2) {
            width
        } else {
            width - 2
        }
        for (w in 0 until wid) {
            val x = w - wid / 2
            val pos = basePos.add(xDirection.directionVec.multiply(x))
            blueprint[pos] = BlueprintTask(fillerMat, isFiller = true)
        }
    }

    private fun generateWalls(basePos: BlockPos, xDirection: Direction) {
        val cb = if (!cornerBlock && width > 2) {
            1
        } else {
            0
        }
        for (h in cb until height) {
            if (cleanRightWall) blueprint[basePos.add(xDirection.directionVec.multiply(width - width / 2)).up(h + 1)] = BlueprintTask(fillerMat, isFiller = true)
            if (cleanLeftWall) blueprint[basePos.add(xDirection.directionVec.multiply(-1 - width / 2)).up(h + 1)] = BlueprintTask(fillerMat, isFiller = true)
        }
    }

    private fun generateRoof(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            val x = w - width / 2
            val pos = basePos.add(xDirection.directionVec.multiply(x))
            blueprint[pos.up(height + 1)] = BlueprintTask(fillerMat, isFiller = true)
        }
    }

    private fun generateCorner(basePos: BlockPos, xDirection: Direction) {
        blueprint[basePos.add(xDirection.directionVec.multiply(-1 - width / 2 + 1)).up()] = BlueprintTask(fillerMat, isFiller = true)
        blueprint[basePos.add(xDirection.directionVec.multiply(width - width / 2 - 1)).up()] = BlueprintTask(fillerMat, isFiller = true)
    }

    private fun generateBackfill(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            for (h in 0 until height) {
                val x = w - width / 2
                val pos = basePos.add(xDirection.directionVec.multiply(x)).up(h + 1)

                if (startingBlockPos.toVec3dCenter().distanceTo(pos.toVec3dCenter()) + 1 < startingBlockPos.toVec3dCenter().distanceTo(currentBlockPos.toVec3dCenter())) {
                    blueprint[pos] = BlueprintTask(fillerMat, isFiller = true)
                }
            }
        }
    }

    private fun isRail(w: Int) = railing && w !in 1 until width - 1

    private fun generateFlat(basePos: BlockPos) {
        // Base
        for (w1 in 0 until width) {
            for (w2 in 0 until width) {
                val x = w1 - width / 2
                val z = w2 - width / 2
                val pos = basePos.add(x, 0, z)

                blueprint[pos] = BlueprintTask(material)
            }
        }

        // Clear
        if (!clearSpace) return
        for (w1 in -width..width) {
            for (w2 in -width..width) {
                for (y in 1 until height) {
                    val x = w1 - width / 2
                    val z = w2 - width / 2
                    val pos = basePos.add(x, y, z)

                    blueprint[pos] = BlueprintTask(Blocks.AIR)
                }
            }
        }
    }

    fun isInsideBlueprint(pos: BlockPos): Boolean {
        return blueprint.containsKey(pos)
    }

    fun isInsideBlueprintBuild(pos: BlockPos): Boolean {
        return blueprint[pos]?.let { it.targetBlock == material } ?: false
    }
}