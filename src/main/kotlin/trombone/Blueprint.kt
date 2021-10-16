package trombone

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
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.commons.extension.ceilToInt
import com.lambda.commons.extension.floorToInt
import net.minecraft.block.Block
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Trombone.Mode

object Blueprint {
    val blueprint = LinkedHashMap<BlockPos, Block>()

    fun generateBluePrint() {
        blueprint.clear()
        val basePos = currentBlockPos.down()

        if (mode != Mode.FLAT) {
            val zDirection = startingDirection
            val xDirection = zDirection.clockwise(if (zDirection.isDiagonal) 1 else 2)

            for (x in -maxReach.floorToInt() * 5..maxReach.ceilToInt() * 5) {
                val thisPos = basePos.add(zDirection.directionVec.multiply(x))
                if (clearSpace) generateClear(thisPos, xDirection)
                if (mode == Mode.TUNNEL) {
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
            if (mode == Mode.TUNNEL && (!cleanFloor || backfill)) {
                if (startingDirection.isDiagonal) {
                    for (x in 0..maxReach.floorToInt()) {
                        val pos = basePos.add(zDirection.directionVec.multiply(x))
                        blueprint[pos] = fillerMat
                        blueprint[pos.add(startingDirection.clockwise(7).directionVec)] = fillerMat
                    }
                } else {
                    for (x in 0..maxReach.floorToInt()) {
                        blueprint[basePos.add(zDirection.directionVec.multiply(x))] = fillerMat
                    }
                }
            }
        } else {
            generateFlat(basePos)
        }
    }

    private fun generateClear(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            for (h in 0 until height) {
                val x = w - width / 2
                val pos = basePos.add(xDirection.directionVec.multiply(x)).up(h)

                if (mode == Mode.HIGHWAY && h == 0 && isRail(w)) {
                    continue
                }

                if (mode == Mode.HIGHWAY) {
                    blueprint[pos] = Blocks.AIR
                } else {
                    if (!(isRail(w) && h == 0 && !cornerBlock && width > 2)) blueprint[pos.up()] = Blocks.AIR
                }
            }
        }
    }

    private fun generateBase(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            val x = w - width / 2
            val pos = basePos.add(xDirection.directionVec.multiply(x))

            if (mode == Mode.HIGHWAY && isRail(w)) {
                if (!cornerBlock && width > 2 && startingDirection.isDiagonal) blueprint[pos] = fillerMat
                val startHeight = if (cornerBlock && width > 2) 0 else 1
                for (y in startHeight..railingHeight) {
                    blueprint[pos.up(y)] = material
                }
            } else {
                blueprint[pos] = material
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
            blueprint[pos] = fillerMat
        }
    }

    private fun generateWalls(basePos: BlockPos, xDirection: Direction) {
        val cb = if (!cornerBlock && width > 2) {
            1
        } else {
            0
        }
        for (h in cb until height) {
            if (cleanRightWall) blueprint[basePos.add(xDirection.directionVec.multiply(width - width / 2)).up(h + 1)] = fillerMat
            if (cleanLeftWall) blueprint[basePos.add(xDirection.directionVec.multiply(-1 - width / 2)).up(h + 1)] = fillerMat
        }
    }

    private fun generateRoof(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            val x = w - width / 2
            val pos = basePos.add(xDirection.directionVec.multiply(x))
            blueprint[pos.up(height + 1)] = fillerMat
        }
    }

    private fun generateCorner(basePos: BlockPos, xDirection: Direction) {
        blueprint[basePos.add(xDirection.directionVec.multiply(-1 - width / 2 + 1)).up()] = fillerMat
        blueprint[basePos.add(xDirection.directionVec.multiply(width - width / 2 - 1)).up()] = fillerMat
    }

    private fun generateBackfill(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            for (h in 0 until height) {
                val x = w - width / 2
                val pos = basePos.add(xDirection.directionVec.multiply(x)).up(h + 1)

                if (Pathfinder.startingBlockPos.distanceTo(pos) < Pathfinder.startingBlockPos.distanceTo(Pathfinder.currentBlockPos)) {
                    blueprint[pos] = fillerMat
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

                blueprint[pos] = material
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

                    blueprint[pos] = Blocks.AIR
                }
            }
        }
    }

    fun isInsideBlueprint(pos: BlockPos): Boolean {
        return blueprint.containsKey(pos)
    }

    fun isInsideBlueprintBuild(pos: BlockPos): Boolean {
        return blueprint[pos]?.let { it == material } ?: false
    }
}