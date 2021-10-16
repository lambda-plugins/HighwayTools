package trombone

import HighwayTools.anonymizeStats
import HighwayTools.disableMode
import HighwayTools.disableWarnings
import HighwayTools.fillerMat
import HighwayTools.height
import HighwayTools.ignoreBlocks
import HighwayTools.info
import HighwayTools.material
import HighwayTools.mode
import HighwayTools.multiBuilding
import HighwayTools.proxyCommand
import HighwayTools.rubberbandTimeout
import HighwayTools.usingProxy
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.combat.AutoLog
import com.lambda.client.module.modules.misc.AntiAFK
import com.lambda.client.module.modules.misc.AutoObsidian
import com.lambda.client.module.modules.movement.AntiHunger
import com.lambda.client.module.modules.movement.Velocity
import com.lambda.client.module.modules.player.AutoEat
import com.lambda.client.module.modules.player.LagNotifier
import com.lambda.client.process.PauseProcess
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.onMainThreadSafe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.init.SoundEvents
import net.minecraft.util.text.TextComponentString
import net.minecraft.world.EnumDifficulty
import trombone.Pathfinder.currentBlockPos
import trombone.Pathfinder.startingBlockPos
import trombone.Pathfinder.startingDirection
import trombone.Statistics.totalBlocksBroken
import trombone.Statistics.totalBlocksPlaced
import trombone.Trombone.Mode
import trombone.Trombone.module
import kotlin.math.abs

object IO {
    enum class DisableMode {
        NONE, ANTI_AFK, LOGOUT
    }

    enum class DebugMessages {
        OFF, IMPORTANT, ALL
    }

    fun SafeClientEvent.pauseCheck(): Boolean =
        !Pathfinder.rubberbandTimer.tick(rubberbandTimeout.toLong(), false) ||
            player.inventory.isEmpty ||
            PauseProcess.isActive ||
            AutoObsidian.isActive() ||
            (world.difficulty == EnumDifficulty.PEACEFUL &&
                player.dimension == 1 &&
                @Suppress("UNNECESSARY_SAFE_CALL")
                player.serverBrand?.contains("2b2t") == true)

    fun printEnable() {
        if (info) {
            MessageSendHelper.sendRawChatMessage("    §9> §7Direction: §a${startingDirection.displayName} / ${startingDirection.displayNameXY}§r")

            if (!anonymizeStats) {
                if (startingDirection.isDiagonal) {
                    MessageSendHelper.sendRawChatMessage("    §9> §7Axis offset: §a%,d %,d§r".format(startingBlockPos.x, startingBlockPos.z))

                    if (abs(startingBlockPos.x) != abs(startingBlockPos.z)) {
                        MessageSendHelper.sendRawChatMessage("    §9> §cYou may have an offset to diagonal highway position!")
                    }
                } else {
                    if (startingDirection == Direction.NORTH || startingDirection == Direction.SOUTH) {
                        MessageSendHelper.sendRawChatMessage("    §9> §7Axis offset: §a%,d§r".format(startingBlockPos.x))
                    } else {
                        MessageSendHelper.sendRawChatMessage("    §9> §7Axis offset: §a%,d§r".format(startingBlockPos.z))
                    }

                }
            }

            if (!disableWarnings) {
                if (startingBlockPos.y != 120 && mode != Mode.TUNNEL) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cCheck altitude and make sure to build at Y: 120 for the correct height")
                }

                if (AntiHunger.isEnabled) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cAntiHunger does slow down block interactions.")
                }

                if (LagNotifier.isDisabled) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cYou should activate LagNotifier to make the bot stop on server lag.")
                }

                if (AutoEat.isDisabled) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cYou should activate AutoEat to not die on starvation.")
                }

                if (AutoLog.isDisabled) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cYou should activate AutoLog to prevent most deaths when afk.")
                }

                if (multiBuilding && Velocity.isDisabled) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cMake sure to enable Velocity to not get pushed from your mates.")
                }

                if (material == fillerMat) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cMake sure to use §aTunnel Mode§c instead of having same material for both main and filler!")
                }

                if (mode == Mode.HIGHWAY && height < 3) {
                    MessageSendHelper.sendRawChatMessage("    §9> §cYou may increase the height to at least 3")
                }
            }
        }
    }

    fun printDisable() {
        if (info) {
            MessageSendHelper.sendRawChatMessage("    §9> §7Placed blocks: §a%,d§r".format(totalBlocksPlaced))
            MessageSendHelper.sendRawChatMessage("    §9> §7Destroyed blocks: §a%,d§r".format(totalBlocksBroken))
            MessageSendHelper.sendRawChatMessage("    §9> §7Distance: §a%,d§r".format(startingBlockPos.distanceTo(currentBlockPos).toInt()))
        }
    }

    fun printSettings() {
        StringBuilder(ignoreBlocks.size + 1).run {
            append("${module.chatName} Settings" +
                "\n §9> §rMain material: §7${material.localizedName}" +
                "\n §9> §rFiller material: §7${fillerMat.localizedName}" +
                "\n §9> §rIgnored Blocks:")

            ignoreBlocks.forEach {
                append("\n     §9> §7$it")
            }

            MessageSendHelper.sendChatMessage(toString())
        }
    }

    fun SafeClientEvent.disableError(message: String) {
        MessageSendHelper.sendChatMessage("${module.chatName} $message")
        mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
        module.disable()
        when (disableMode) {
            DisableMode.ANTI_AFK -> {
                MessageSendHelper.sendChatMessage("${module.chatName} Going into AFK mode.")
                AntiAFK.enable()
            }
            DisableMode.LOGOUT -> {
                MessageSendHelper.sendChatMessage("${module.chatName} CAUTION: Logging of in 1 minute!")
                defaultScope.launch {
                    delay(6000L)
                    if (disableMode == DisableMode.LOGOUT && module.isEnabled) {
                        onMainThreadSafe {
                            if (usingProxy) {
                                player.sendChatMessage(proxyCommand)
                            } else {
                                connection.networkManager.closeChannel(TextComponentString("Done building highways."))
                            }
                        }
                    }
                }
            }
            DisableMode.NONE -> {
                // Nothing
            }
        }
    }
}