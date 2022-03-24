import com.lambda.client.command.ClientCommand
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage
import net.minecraft.util.math.BlockPos
import trombone.IO.printSettings
import trombone.Pathfinder
import trombone.Pathfinder.cleanStashPos
import trombone.Pathfinder.distancePending
import trombone.Pathfinder.netherPortalPos
import trombone.Pathfinder.stashBlockPos
import trombone.Pathfinder.updateProcess

object HighwayToolsCommand : ClientCommand(
    name = "highwaytools",
    alias = arrayOf("ht", "hwt", "high"),
    description = "Customize settings of HighwayTools."
) {

    init {
        literal("add", "new", "+") {
            block("block") { blockArg ->
                execute("Adds a block to ignore list") {
                    val added = HighwayTools.ignoreBlocks.add(blockArg.value.registryName.toString())
                    if (added) {
                        printSettings()
                        sendChatMessage("Added &7${blockArg.value.localizedName}&r to ignore list.")
                    } else {
                        sendChatMessage("&7${blockArg.value.localizedName}&r is already ignored.")
                    }
                }
            }
        }

        literal("remove", "rem", "-", "del") {
            block("block") { blockArg ->
                execute("Removes a block from ignore list") {
                    val removed = HighwayTools.ignoreBlocks.remove(blockArg.value.registryName.toString())
                    if (removed) {
                        printSettings()
                        sendChatMessage("Removed &7${blockArg.value.localizedName}&r from ignore list.")
                    } else {
                        sendChatMessage("&7${blockArg.value.localizedName}&r is not yet ignored.")
                    }
                }
            }
        }

//        literal("from", "start") {
//            blockPos("position") { blockPosArg ->
//                execute("Sets starting coordinates") {
//                    // ToDo: Make starting position for next instance
////                    HighwayTools.startingPos = blockPosArg.value
//                }
//            }
//        }

//        literal("to", "stop") {
//            blockPos("position") { blockPosArg ->
//                execute("Sets stopping coordinates and starts bot") {
//                    if (HighwayTools.isEnabled) {
//                        sendChatMessage("Run this command when the bot is not running")
//                    } else {
//                        HighwayTools.targetBlockPos = blockPosArg.value
//                        sendChatMessage("Started HighwayTools with target @(${blockPosArg.value.asString()})")
//                        HighwayTools.enable()
//                    }
//                }
//            }
//        }

        literal("distance") {
            int("distance") { distanceArg ->
                execute("Sets the target distance until the bot stops") {
                    distancePending = distanceArg.value
                    sendChatMessage("HighwayTools will stop after (${distanceArg.value}) blocks distance. To remove the limit use distance 0")
                }
            }
        }

        literal("stash", "s") {
            int("x:Int> <y:Int> <z: Int> Nether portal pos: <x:Int> <y:Int> <z") { xArg ->
                execute("Not enough arguments") {
                    sendChatMessage("You need to specify chest y and z, and Nether portal x, y and z pos")
                }

                int("y:int> <z:Int> Nether portal pos: <x:Int> <y:Int> <z") { yArg ->
                    execute("Not enough arguments") {
                        sendChatMessage("You need to specify chest z and Nether portal x, y and z pos")
                    }

                    int("z: Int> Nether portal pos: <x:Int> <y:Int> <z") { zArg ->
                        execute("You need to specify nether portal x, y and z pos") {
                        }

                        int("x:Int> <y:Int> <z") { xNArg ->
                            execute("Not enough arguments") {
                                sendChatMessage("You need to specify nether portal x, y and z pos")
                            }

                            int("y:int> <z") { yNArg ->
                                execute("Not enough arguments") {
                                    sendChatMessage("You need to specify nether portal x, y and z pos")
                                }

                                int("z") { zNArg ->
                                    execute("Sets the stash position for the bots refill") {
                                        stashBlockPos = BlockPos(xArg.value, yArg.value, zArg.value)
                                        cleanStashPos = mutableListOf(xArg.value, yArg.value, zArg.value)
                                        netherPortalPos = mutableListOf(xNArg.value, yNArg.value, zNArg.value)
                                        sendChatMessage("HighwayTools will go to ${cleanStashPos[0]} ${cleanStashPos[1]} ${cleanStashPos[2]} to restock.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        literal("forceRestock", "fr") {
            execute("Done") {
                Pathfinder.MovementState.RESTOCK
                updateProcess()
                sendChatMessage("Force restocked")
            }
        }

        literal("material", "mat") {
            block("block") { blockArg ->
                execute("Sets a block as main material") {
                    HighwayTools.material = blockArg.value
                    sendChatMessage("Set your building material to &7${blockArg.value.localizedName}&r.")
                }
            }
        }

        literal("filler", "fil") {
            block("block") { blockArg ->
                execute("Sets a block as filler material") {
                    HighwayTools.fillerMat = blockArg.value
                    sendChatMessage("Set your filling material to &7${blockArg.value.localizedName}&r.")
                }
            }
        }

        literal("food", "fd") {
            item("item") { itemArg ->
                execute("Sets a type of food") {
                    HighwayTools.food = itemArg.value
                    sendChatMessage("Set your food item to &7${itemArg.value.registryName}&r.")
                }
            }
        }

        execute("Print settings") {
            printSettings()
        }
    }
}