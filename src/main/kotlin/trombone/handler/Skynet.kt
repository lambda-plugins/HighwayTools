package trombone.handler

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

    val botSet = LinkedHashSet<Bot>()
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
        if (whisperTimer.tick(whisperDelay * 1L) && pendingWhispers.isNotEmpty()) {
            val pendingCommand = pendingWhispers.poll()
            botSet.forEach {
                if (it.name == pendingCommand.second) sendServerMessage(pendingCommand.first)
            }
        }

        val players = LinkedHashSet(world.playerEntities)

        players.removeIf {
            it.isFakeOrSelf || (!friends && FriendManager.isFriend(it.name))
        }

        players.forEach { player ->
            if (botSet.any { it.player == player }) {
                botSet.add(Bot(player, player.name, Rank.NONE, Job.NONE, 0))
                if (module.isEnabled) handshake(player.name)
            }
        }

        botSet.removeIf {
            !players.contains(it.player)
        }
    }

    private fun addPendingCommand(command: Command, player: String, data: String = "") {
        val commandInfo = if (data.isBlank()) {
            "$command"
        } else {
            "$command > $data"
        }

        val commandMessage = "$protocolPrefix ${toBase64(commandInfo)}"
        if (debugLog) MessageSendHelper.sendChatMessage("$protocolPrefix $player > $commandInfo")
        if (!suppressWhisper) pendingWhispers.add(Pair("/w $player $commandMessage", player))
    }

    private fun handshake(player: String) {
        addPendingCommand(Command.HANDSHAKE, player, "$rank $job $lane ${botSet.size}")
    }

    private fun assignStatus(player: String, botRank: Rank, botJob: Job, botLane: Int) {
        addPendingCommand(Command.ASSIGN_STATUS, player, "$botRank $botJob $botLane")
    }

    private fun SafeClientEvent.isCommand(args: List<String>): Boolean {
        return args[0] != player.name && args[1] == "whispers:" && args[2].startsWith(protocolPrefix)
    }

    private fun handleCommand(player: String, command: String) {
        val decoded = fromBase64(command).split(" ")
        if (debugLog) MessageSendHelper.sendChatMessage("$protocolPrefix $player > ${fromBase64(command)}")

        when (Command.valueOf(decoded[0])) {
            Command.HANDSHAKE -> {
                if (player != "Avanatiker" || Rank.valueOf(decoded[1]) != Rank.MASTER) {
                    var index = 0
                    botSet.forEach {
                        if (it.name == player) {
                            it.rank = Rank.SLAVE
                            it.job = Job.PAVER
                            it.lane = index.rem(width - 2)
                            index = botSet.indexOf(it)
                        }
                    }
                    assignStatus(player, Rank.SLAVE, Job.PAVER, index.rem(width - 2))
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
        return if (skynet) {
            pos.add(startingDirection.clockwise(7).directionVec.multiply(testLane))
        } else {
            pos
        }
    }
}