package trombone.handler

import HighwayTools.chatName
import HighwayTools.debugLog
import HighwayTools.friends
import HighwayTools.noWhispersShown
import HighwayTools.skynet
import HighwayTools.suppressWhisper
import HighwayTools.testLane
import HighwayTools.whisperDelay
import HighwayTools.width
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.FriendManager
import com.lambda.client.util.EntityUtils.isFakeOrSelf
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage
import com.lambda.client.util.text.MessageSendHelper.sendServerMessage
import com.sun.jna.Native.toByteArray
import net.minecraft.util.math.BlockPos
import net.minecraftforge.client.event.ClientChatReceivedEvent
import trombone.Bot
import trombone.Pathfinder.startingDirection
import trombone.Trombone.module
import java.util.*
import kotlin.collections.LinkedHashSet

object Skynet {
    const val protocolPrefix = "[HTPv1.0]"

    private enum class Command {
        HANDSHAKE, ASSIGN_STATUS
    }

    enum class Job {
        NONE, DIGGER, PAVER, SCAFFOLDER
    }

    enum class Rank {
        NONE, MASTER, SLAVE
    }

    val bots = LinkedHashSet<Bot>()
    var rank = Rank.NONE
    var job = Job.NONE
    var lane = 0
    val whisperTimer = TickTimer(TimeUnit.TICKS)
    val pendingWhispers: Queue<Pair<String, String>> = LinkedList()

    fun SafeClientEvent.handleChatEvent(event: ClientChatReceivedEvent) {
        val args = event.message.unformattedText.split(" ")
        if (args.size > 2) {
            if (isCommand(args)) {
                handleCommand(args[0], args[3])
                if (noWhispersShown) event.isCanceled = true
            }
        }
    }

    fun SafeClientEvent.skynetHandler() {
        if (pendingWhispers.isNotEmpty()
            && whisperTimer.tick(whisperDelay * 1L)) {
            val pendingCommand = pendingWhispers.poll()
            bots.forEach {
                if (it.name == pendingCommand.second) sendServerMessage(pendingCommand.first)
            }
        }

        val currentPlayers = LinkedHashSet(world.playerEntities)

        currentPlayers.removeIf {
            it.isFakeOrSelf || (friends && !FriendManager.isFriend(it.name))
        }

        currentPlayers.forEach { thatPlayer ->
            if (bots.none { it.player == thatPlayer }) {
                bots.add(Bot(thatPlayer, thatPlayer.name, Rank.NONE, Job.NONE, 0))
                if (module.isEnabled) handshake(thatPlayer.name)
            }
        }

        bots.removeIf {
            !currentPlayers.contains(it.player)
        }
    }

    private fun addPendingCommand(command: Command, player: String, data: String = "") {
        val commandInfo = if (data.isBlank()) {
            "$command"
        } else {
            "$command $data"
        }

        val commandMessage = "$protocolPrefix ${toBase64(commandInfo)}"
        if (debugLog) sendChatMessage("$protocolPrefix To $player > $commandInfo")
        if (!suppressWhisper) pendingWhispers.add(Pair("/w $player $commandMessage", player))
    }

    private fun handshake(player: String) {
        addPendingCommand(Command.HANDSHAKE, player, "$rank $job $lane ${bots.size}")
    }

    private fun assignStatus(player: String, botRank: Rank, botJob: Job, botLane: Int) {
        addPendingCommand(Command.ASSIGN_STATUS, player, "$botRank $botJob $botLane")
    }

    private fun SafeClientEvent.isCommand(args: List<String>): Boolean {
        return args[0] != player.name && args[1] == "whispers:" && args[2].startsWith(protocolPrefix)
    }

    private fun handleCommand(player: String, command: String) {
        val decoded = fromBase64(command).split(" ")
        if (debugLog) sendChatMessage("$protocolPrefix From $player > ${fromBase64(command)}")

        when (Command.valueOf(decoded[0])) {
            Command.HANDSHAKE -> {
                if (player != "Avanatiker" && Rank.valueOf(decoded[1]) != Rank.MASTER) {
                    bots.firstOrNull { it.name == player }?.let {
                        /* Remote */
                        it.rank = Rank.SLAVE
                        it.job = Job.PAVER
                        it.lane = bots.indexOf(it).rem(width - 2)

                        assignStatus(player, it.rank, it.job, it.lane)

                        /* Self */
                        rank = Rank.MASTER
                        job = Job.PAVER
                        lane = 0

                        /* Update remote */
                        handshake(player)
                    } ?: run {
                        sendChatMessage("$chatName Bot $player not indexed (1)")
                    }
                } else {
                    bots.firstOrNull { it.name == player }?.let {
                        it.rank = Rank.valueOf(decoded[1])
                        it.job = Job.valueOf(decoded[2])
                        it.lane = decoded[3].toInt()
                    } ?: run {
                        sendChatMessage("$chatName Bot $player not indexed (2)")
                    }
                }
            }
            Command.ASSIGN_STATUS -> {
                rank = Rank.valueOf(decoded[1])
                job = Job.valueOf(decoded[2])
                lane = decoded[3].toInt()
            }
        }
    }

    private fun toBase64(string: String): String {
        return Base64.getEncoder().encodeToString(toByteArray(string))
    }

    private fun fromBase64(string: String): String {
        return String(Base64.getDecoder().decode(string))
    }

    fun getLaneOffset(pos: BlockPos): BlockPos {
        return pos.add(startingDirection.clockwise(1).directionVec.multiply(testLane))
    }
}