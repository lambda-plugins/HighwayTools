import com.lambda.client.command.ClientCommand
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage
import net.minecraft.util.math.BlockPos
import trombone.IO.printSettings
import trombone.Pathfinder.distancePending
import trombone.Pathfinder.stashNetherPos
import trombone.Pathfinder.stashPos

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
            int("x> <y> <z> Nether portal <x> <y> <z") { xArg ->
                execute("Not enough arguments") {
                    sendChatMessage("You need to specify chest y and z, and Nether portal x, y and z pos")
                }

                int("y> <z> Nether portal <x> <y> <z") { yArg ->
                    execute("Not enough arguments") {
                        sendChatMessage("You need to specify chest z and Nether portal x, y and z pos")
                    }

                    int("z> Nether portal <x> <y> <z") { zArg ->
                        execute("Sets the stash position for the bots refill") {
                            sendChatMessage("You need to specify Nether portal x, y and z pos")
                        }

                        int("x> <y> <z") { xNetherArg ->
                            execute("Not enough arguments") {
                                sendChatMessage("You need to specify Nether portal x, y and z pos")
                            }

                            int("y> <z") { yNetherArg ->
                                execute("Not enough arguments") {
                                    sendChatMessage("You need to specify Nether portal y and z pos")
                                }

                                int("z") { zNetherArg ->
                                    execute("Sets the stash position for the bots refill") {
                                        stashPos = BlockPos(xArg.value, yArg.value, zArg.value)
                                        stashNetherPos = BlockPos(xNetherArg.value, yNetherArg.value, zNetherArg.value)
                                        sendChatMessage("HighwayTools will go to ${stashPos.toString().replace("BlockPos".toRegex(), "").replace("\\{([^}]+)}".toRegex(), "$1")} to restock.")
                                        sendChatMessage("And will go through nether portal at ${stashNetherPos.toString().replace("BlockPos".toRegex(), "").replace("\\{([^}]+)}".toRegex(), "$1")}.")
                                    }
                                }
                            }
                        }
                    }
                }
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