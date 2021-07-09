package trombone

import net.minecraft.entity.player.EntityPlayer
import trombone.handler.Skynet

class Bot(
    val player: EntityPlayer,
    val name: String,
    var rank: Skynet.Rank,
    var job: Skynet.Job,
    var lane: Int
)