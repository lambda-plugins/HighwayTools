import baritone.api.pathing.goals.GoalNear
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.module.Category
import com.lambda.client.module.modules.client.Hud.primaryColor
import com.lambda.client.module.modules.client.Hud.secondaryColor
import com.lambda.client.module.modules.combat.AutoLog
import com.lambda.client.module.modules.misc.AntiAFK
import com.lambda.client.module.modules.misc.AutoObsidian
import com.lambda.client.module.modules.movement.AntiHunger
import com.lambda.client.module.modules.movement.Velocity
import com.lambda.client.module.modules.player.AutoEat
import com.lambda.client.module.modules.player.InventoryManager
import com.lambda.client.module.modules.player.LagNotifier
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.process.PauseProcess
import com.lambda.client.setting.settings.impl.collection.CollectionSetting
import com.lambda.client.util.*
import com.lambda.client.util.EntityUtils.flooredPosition
import com.lambda.client.util.EntityUtils.getDroppedItems
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.ProjectionUtils
import com.lambda.client.util.graphics.font.FontRenderAdapter
import com.lambda.client.util.graphics.font.TextComponent
import com.lambda.client.util.items.*
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.math.VectorUtils
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.multiply
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.math.isInSight
import com.lambda.client.util.text.MessageSendHelper.sendChatMessage
import com.lambda.client.util.text.MessageSendHelper.sendRawChatMessage
import com.lambda.client.util.threads.*
import com.lambda.client.util.world.*
import com.lambda.commons.extension.ceilToInt
import com.lambda.commons.extension.floorToInt
import com.lambda.event.listener.listener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.init.Blocks
import net.minecraft.init.Enchantments
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.inventory.Slot
import net.minecraft.item.*
import net.minecraft.network.play.client.CPacketClientStatus
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketOpenWindow
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.network.play.server.SPacketWindowItems
import net.minecraft.stats.StatList
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.NonNullList
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraft.util.text.TextComponentString
import net.minecraft.world.EnumDifficulty
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.lwjgl.opengl.GL11
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random.Default.nextInt

/**
 * @author Avanatiker
 * @since 20/08/2020
 */
internal object HighwayTools : PluginModule(
    name = "HighwayTools",
    description = "Be the grief a step a head.",
    category = Category.MISC,
    alias = arrayOf("HT", "HWT"),
    modulePriority = 10,
    pluginMain = HighwayToolsPlugin
) {
    private val page by setting("Page", Page.BUILD, description = "Switch between the setting pages")

    private val defaultIgnoreBlocks = linkedSetOf(
        "minecraft:standing_sign",
        "minecraft:wall_sign",
        "minecraft:standing_banner",
        "minecraft:wall_banner",
        "minecraft:bedrock",
        "minecraft:end_portal",
        "minecraft:end_portal_frame",
        "minecraft:portal"
    )

    // build settings
    private val mode by setting("Mode", Mode.HIGHWAY, { page == Page.BUILD }, description = "Choose the structure")
    private val width by setting("Width", 6, 1..11, 1, { page == Page.BUILD }, description = "Sets the width of blueprint")
    private val height by setting("Height", 4, 1..6, 1, { page == Page.BUILD && clearSpace }, description = "Sets height of blueprint")
    private val backfill by setting("Backfill", false, { page == Page.BUILD && mode == Mode.TUNNEL }, description = "Fills the tunnel behind you")
    private val clearSpace by setting("Clear Space", true, { page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Clears out the tunnel if necessary")
    private val cleanFloor by setting("Clean Floor", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the tunnels floor")
    private val cleanWalls by setting("Clean Walls", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the tunnels walls")
    private val cleanRoof by setting("Clean Roof", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the tunnels roof")
    private val cleanCorner by setting("Clean Corner", false, { page == Page.BUILD && mode == Mode.TUNNEL && !cornerBlock && !backfill && width > 2 }, description = "Cleans up the tunnels corner")
    private val cornerBlock by setting("Corner Block", false, { page == Page.BUILD && (mode == Mode.HIGHWAY || (mode == Mode.TUNNEL && !backfill && width > 2)) }, description = "If activated will break the corner in tunnel or place a corner while paving")
    private val railing by setting("Railing", true, { page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Adds a railing / rim / border to the highway")
    private val railingHeight by setting("Railing Height", 1, 1..4, 1, { railing && page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Sets height of railing")
    private val materialSaved = setting("Material", "minecraft:obsidian", { false })
    private val fillerMatSaved = setting("FillerMat", "minecraft:netherrack", { false })
    val ignoreBlocks = setting(CollectionSetting("IgnoreList", defaultIgnoreBlocks, { false }))

    // behavior settings
    private val interacting by setting("Rotation Mode", RotationMode.SPOOF, { page == Page.BEHAVIOR }, description = "Force view client side, only server side or no interaction at all")
    private val dynamicDelay by setting("Dynamic Place Delay", true, { page == Page.BEHAVIOR }, description = "Slows down on failed placement attempts")
    private val placeDelay by setting("Place Delay", 3, 1..20, 1, { page == Page.BEHAVIOR }, description = "Sets the delay ticks between placement tasks")
    private val breakDelay by setting("Break Delay", 1, 1..20, 1, { page == Page.BEHAVIOR }, description = "Sets the delay ticks between break tasks")
    private val illegalPlacements by setting("Illegal Placements", false, { page == Page.BEHAVIOR }, description = "Do not use on 2b2t. Tries to interact with invisible surfaces")
    private val bridging by setting("Bridging", true, { page == Page.BEHAVIOR }, description = "Tries to bridge / scaffold when stuck placing")
    private val instantMine by setting("Instant Mine", false, { page == Page.BEHAVIOR }, description = "Instant mine NCP exploit.")
    private val multiBuilding by setting("Shuffle Tasks", false, { page == Page.BEHAVIOR }, description = "Only activate when working with several players")
    private val taskTimeout by setting("Task Timeout", 8, 0..20, 1, { page == Page.BEHAVIOR }, description = "Timeout for waiting for the server to try again")
    private val rubberbandTimeout by setting("Rubberband Timeout", 50, 5..100, 5, { page == Page.BEHAVIOR }, description = "Timeout for pausing after a lag")
    private val maxReach by setting("Max Reach", 4.9f, 1.0f..7.0f, 0.1f, { page == Page.BEHAVIOR }, description = "Sets the range of the blueprint. Decrease when tasks fail!")
    private val maxBreaks by setting("Multi Break", 1, 1..5, 1, { page == Page.BEHAVIOR }, description = "EXPERIMENTAL: Breaks multiple instant breaking blocks per tick in view")
    private val limitOrigin by setting("Limited by", LimitMode.FIXED, { page == Page.BEHAVIOR }, description = "Changes the origin of limit: Client / Server TPS")
    private val limitFactor by setting("Limit Factor", 1.0f, 0.5f..2.0f, 0.01f, { page == Page.BEHAVIOR }, description = "EXPERIMENTAL: Factor for TPS which acts as limit for maximum breaks per second.")
    private val placementSearch by setting("Place Deep Search", 2, 1..4, 1, { page == Page.BEHAVIOR }, description = "EXPERIMENTAL: Attempts to find a support block for placing against")

    // storage management
    private val storageManagement by setting("Manage Storage", true, { page == Page.STORAGE_MANAGEMENT }, description = "Choose to interact with container using only packets.")
    private val leaveEmptyShulkers by setting("Leave Empty Shulkers", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Does not break empty shulkers.")
    private val grindObsidian by setting("Grind Obsidian", true, { page == Page.STORAGE_MANAGEMENT }, description = "Destroy Ender Chests to obtain Obsidian.")
    private val saveMaterial by setting("Save Material", 12, 0..64, 1, { page == Page.STORAGE_MANAGEMENT }, description = "How many material blocks are saved")
    private val saveTools by setting("Save Tools", 1, 0..36, 1, { page == Page.STORAGE_MANAGEMENT }, description = "How many tools are saved")
    private val saveEnder by setting("Save Ender Chests", 1, 0..64, 1, { page == Page.STORAGE_MANAGEMENT }, description = "How many ender chests are saved")
    private val disableMode by setting("Disable Mode", DisableMode.NONE, { page == Page.STORAGE_MANAGEMENT }, description = "Choose action when bot is out of materials or tools")
    private val usingProxy by setting("Proxy", false, { disableMode == DisableMode.LOGOUT && page == Page.STORAGE_MANAGEMENT }, description = "Enable this if you are using a proxy to call the given command")
    private val proxyCommand by setting("Proxy Command", "/dc", { usingProxy && disableMode == DisableMode.LOGOUT && page == Page.STORAGE_MANAGEMENT }, description = "Command to be sent to log out")
//    private val tryRefreshSlots by setting("Try refresh slot", false, { page == Page.STORAGE_MANAGEMENT }, description = "Clicks a slot on desync")

    // stat settings
    val anonymizeStats by setting("Anonymize", false, { page == Page.STATS }, description = "Censors all coordinates in HUD and Chat")
    private val simpleMovingAverageRange by setting("Moving Average", 60, 5..600, 5, { page == Page.STATS }, description = "Sets the timeframe of the average in seconds")
    private val showSession by setting("Show Session", true, { page == Page.STATS }, description = "Toggles the Session section in HUD")
    private val showLifeTime by setting("Show Lifetime", true, { page == Page.STATS }, description = "Toggles the Lifetime section in HUD")
    private val showPerformance by setting("Show Performance", true, { page == Page.STATS }, description = "Toggles the Performance section in HUD")
    private val showEnvironment by setting("Show Environment", true, { page == Page.STATS }, description = "Toggles the Environment section in HUD")
    private val showTask by setting("Show Task", true, { page == Page.STATS }, description = "Toggles the Task section in HUD")
    private val showEstimations by setting("Show Estimations", true, { page == Page.STATS }, description = "Toggles the Estimations section in HUD")
    private val resetStats = setting("Reset Stats", false, { page == Page.STATS }, description = "Resets the stats")

    // config
    private val fakeSounds by setting("Fake Sounds", true, { page == Page.CONFIG }, description = "Adds artificial sounds to the actions")
    private val info by setting("Show Info", true, { page == Page.CONFIG }, description = "Prints session stats in chat")
    private val printDebug by setting("Show Queue", false, { page == Page.CONFIG }, description = "Shows task queue in HUD")
    private val debugMessages by setting("Debug Messages", DebugMessages.IMPORTANT, { page == Page.CONFIG }, description = "Sets the debug log depth level")
    private val goalRender by setting("Goal Render", false, { page == Page.CONFIG }, description = "Renders the baritone goal")
    private val filled by setting("Filled", true, { page == Page.CONFIG }, description = "Renders colored task surfaces")
    private val outline by setting("Outline", true, { page == Page.CONFIG }, description = "Renders colored task outlines")
    private val popUp by setting("Pop up", true, { page == Page.CONFIG }, description = "Funny render effect")
    private val popUpSpeed by setting("Pop up speed", 150, 0..500, 1, { popUp && page == Page.CONFIG }, description = "Sets speed of the pop up effect")
    private val showDebugRender by setting("Debug Render", false, { page == Page.CONFIG }, description = "Render debug info on tasks")
    private val textScale by setting("Text Scale", 1.0f, 0.0f..4.0f, 0.25f, { showDebugRender && page == Page.CONFIG }, description = "Scale of debug text")
    private val aFilled by setting("Filled Alpha", 26, 0..255, 1, { filled && page == Page.CONFIG }, description = "Sets the opacity")
    private val aOutline by setting("Outline Alpha", 91, 0..255, 1, { outline && page == Page.CONFIG }, description = "Sets the opacity")
    private val thickness by setting("Thickness", 2.0f, 0.25f..4.0f, 0.25f, { outline && page == Page.CONFIG }, description = "Sets thickness of outline")

    enum class Mode {
        HIGHWAY, FLAT, TUNNEL
    }

    private enum class Page {
        BUILD, BEHAVIOR, STORAGE_MANAGEMENT, STATS, CONFIG
    }

    @Suppress("UNUSED")
    private enum class RotationMode {
        OFF, SPOOF, VIEW_LOCK
    }

    private enum class LimitMode {
        FIXED, SERVER
    }

    private enum class DisableMode {
        NONE, ANTI_AFK, LOGOUT
    }

    private enum class DebugMessages {
        OFF, IMPORTANT, ALL
    }

    // internal settings
    var material: Block
        get() = Block.getBlockFromName(materialSaved.value) ?: Blocks.OBSIDIAN
        set(value) {
            materialSaved.value = value.registryName.toString()
        }
    var fillerMat: Block
        get() = Block.getBlockFromName(fillerMatSaved.value) ?: Blocks.NETHERRACK
        set(value) {
            fillerMatSaved.value = value.registryName.toString()
        }
    private var baritoneSettingAllowPlace = false
    private var baritoneSettingAllowBreak = false
    private var baritoneSettingRenderGoal = false

    // Blue print
    private var startingDirection = Direction.NORTH
    private var currentBlockPos = BlockPos(0, -1, 0)
    private var startingBlockPos = BlockPos(0, -1, 0)
    private var targetBlockPos = BlockPos(0, -1, 0)
    var distancePending = 0
    private val blueprint = LinkedHashMap<BlockPos, Block>()

    // State
    private val rubberbandTimer = TickTimer(TimeUnit.TICKS)
    private var active = false
    private var waitTicks = 0
    private var extraPlaceDelay = 0

    // Rotation
    private var lastHitVec = Vec3d.ZERO
    private val rotateTimer = TickTimer(TimeUnit.TICKS)

    // Pathing
    var goal: GoalNear? = null; private set
    private var moveState = MovementState.RUNNING

    // Tasks
    private val pendingTasks = LinkedHashMap<BlockPos, BlockTask>()
    private val doneTasks = LinkedHashMap<BlockPos, BlockTask>()
    private var sortedTasks: List<BlockTask> = emptyList()
    var lastTask: BlockTask? = null; private set
    private var prePrimedPos = BlockPos.NULL_VECTOR
    private var primedPos = BlockPos.NULL_VECTOR

    private var containerTask = BlockTask(BlockPos.ORIGIN, TaskState.DONE, Blocks.AIR, Items.AIR)
    private val shulkerOpenTimer = TickTimer(TimeUnit.TICKS)
    private var grindCycles = 0

    private val packetLimiterMutex = Mutex()
    private val packetLimiter = ArrayDeque<Long>()

    // Stats
    private val simpleMovingAveragePlaces = ArrayDeque<Long>()
    private val simpleMovingAverageBreaks = ArrayDeque<Long>()
    private val simpleMovingAverageDistance = ArrayDeque<Long>()
    private var totalBlocksPlaced = 0
    private var totalBlocksBroken = 0
    private var totalDistance = 0.0
    private var runtimeMilliSeconds = 0
    private var prevFood = 0
    private var foodLoss = 1
    private var materialLeft = 0
    private var fillerMatLeft = 0
    private var lastToolDamage = 0
    private var durabilityUsages = 0
    private var matPlaced = 0
    private var enderMined = 0
    private var netherrackMined = 0
    private var pickaxeBroken = 0

    private val stateUpdateMutex = Mutex()
    private val renderer = ESPRenderer()

    override fun isActive(): Boolean {
        return isEnabled && active
    }

    init {
        shulkerList.forEach {
            ignoreBlocks.add(it.registryName.toString())
        }

        onEnable {
            runSafeR {
                startingBlockPos = player.flooredPosition
                currentBlockPos = startingBlockPos
                startingDirection = Direction.fromEntity(player)
                primedPos = BlockPos.NULL_VECTOR
                prePrimedPos = BlockPos.NULL_VECTOR
                grindCycles = 0

                baritoneSettingAllowPlace = BaritoneUtils.settings?.allowPlace?.value ?: true
                baritoneSettingAllowBreak = BaritoneUtils.settings?.allowBreak?.value ?: true
                BaritoneUtils.settings?.allowPlace?.value = false
                BaritoneUtils.settings?.allowBreak?.value = false

                if (!goalRender) {
                    baritoneSettingRenderGoal = BaritoneUtils.settings?.renderGoal?.value ?: true
                    BaritoneUtils.settings?.renderGoal?.value = false
                }

                pendingTasks.clear()
                containerTask.updateState(TaskState.DONE)
                refreshData()
                printEnable()
            } ?: disable()
        }

        onDisable {
            runSafe {
                BaritoneUtils.settings?.allowPlace?.value = baritoneSettingAllowPlace
                BaritoneUtils.settings?.allowBreak?.value = baritoneSettingAllowBreak
                BaritoneUtils.settings?.renderGoal?.value = baritoneSettingRenderGoal

                active = false
                goal = null
                lastTask = null
                totalDistance += startingBlockPos.distanceTo(currentBlockPos)

                printDisable()
            }
        }

        resetStats.consumers.add { _, it ->
            if (it) resetStats()
            false
        }
    }

    private fun printEnable() {
        if (info) {
            sendRawChatMessage("    §9> §7Direction: §a${startingDirection.displayName} / ${startingDirection.displayNameXY}§r")

            if (!anonymizeStats) {
                if (startingDirection.isDiagonal) {
                    sendRawChatMessage("    §9> §7Axis offset: §a%,d %,d§r".format(startingBlockPos.x, startingBlockPos.z))

                    if (abs(startingBlockPos.x) != abs(startingBlockPos.z)) {
                        sendRawChatMessage("    §9> §cYou may have an offset to diagonal highway position!")
                    }
                } else {
                    if (startingDirection == Direction.NORTH || startingDirection == Direction.SOUTH) {
                        sendRawChatMessage("    §9> §7Axis offset: §a%,d§r".format(startingBlockPos.x))
                    } else {
                        sendRawChatMessage("    §9> §7Axis offset: §a%,d§r".format(startingBlockPos.z))
                    }

                }
            }

            if (startingBlockPos.y != 120 && mode != Mode.TUNNEL) {
                sendRawChatMessage("    §9> §cCheck altitude and make sure to build at Y: 120 for the correct height")
            }

            if (AntiHunger.isEnabled) {
                sendRawChatMessage("    §9> §cAntiHunger does slow down block interactions.")
            }

            if (LagNotifier.isDisabled) {
                sendRawChatMessage("    §9> §cYou should activate LagNotifier to make the bot stop on server lag.")
            }

            if (AutoEat.isDisabled) {
                sendRawChatMessage("    §9> §cYou should activate AutoEat to not die on starvation.")
            }

            if (AutoLog.isDisabled) {
                sendRawChatMessage("    §9> §cYou should activate AutoLog to prevent most deaths when afk.")
            }

            if (multiBuilding && Velocity.isDisabled) {
                sendRawChatMessage("    §9> §cMake sure to enable Velocity to not get pushed from your mates.")
            }

            if (material == fillerMat) {
                sendRawChatMessage("    §9> §cMake sure to use §aTunnel Mode§c instead of having same material for both main and filler!")
            }

            if (mode == Mode.HIGHWAY && height < 3) {
                sendRawChatMessage("    §9> §cYou may increase the height to at least 3")
            }

        }
    }

    private fun printDisable() {
        if (info) {
            sendRawChatMessage("    §9> §7Placed blocks: §a%,d§r".format(totalBlocksPlaced))
            sendRawChatMessage("    §9> §7Destroyed blocks: §a%,d§r".format(totalBlocksBroken))
            sendRawChatMessage("    §9> §7Distance: §a%,d§r".format(startingBlockPos.distanceTo(currentBlockPos).toInt()))
        }
    }

    init {
        safeListener<PacketEvent.Receive> { event ->
            when (event.packet) {
                is SPacketBlockChange -> {
                    val packet = event.packet as SPacketBlockChange
                    val pos = packet.blockPosition
                    if (!isInsideBlueprint(pos)) return@safeListener

                    val prev = world.getBlockState(pos).block
                    val new = packet.getBlockState().block

                    if (prev != new) {
                        val task = if (pos == containerTask.blockPos) {
                            containerTask
                        } else {
                            pendingTasks[pos] ?: return@safeListener
                        }

                        when (task.taskState) {
                            TaskState.PENDING_BREAK, TaskState.BREAKING -> {
                                if (new == Blocks.AIR) {
                                    runBlocking {
                                        stateUpdateMutex.withLock {
                                            task.updateState(TaskState.BROKEN)
                                        }
                                    }
                                }
                            }
                            TaskState.PENDING_PLACE -> {
                                if (task.block != Blocks.AIR && task.block == new) {
                                    runBlocking {
                                        stateUpdateMutex.withLock {
                                            task.updateState(TaskState.PLACED)
                                        }
                                    }
                                }
                            }
                            else -> {
                                // Ignored
                            }
                        }
                    }
                }
                is SPacketPlayerPosLook -> {
                    rubberbandTimer.reset()
                }
                is SPacketOpenWindow -> {
                    val packet = event.packet as SPacketOpenWindow
                    if (containerTask.taskState != TaskState.DONE &&
                        packet.guiId == "minecraft:shulker_box" && containerTask.isShulker ||
                        packet.guiId == "minecraft:container" && !containerTask.isShulker) {
                        containerTask.isOpen = true
                    }
                }
                is SPacketWindowItems -> {
                    if (containerTask.isOpen) containerTask.isLoaded = true
                }
                else -> {
                    // Nothing
                }
            }
        }

        listener<RenderWorldEvent> {
            renderer.clear()
            renderer.aFilled = if (filled) aFilled else 0
            renderer.aOutline = if (outline) aOutline else 0
            renderer.thickness = thickness
            val currentTime = System.currentTimeMillis()

//        renderer.add(world.getBlockState(currentBlockPos).getSelectedBoundingBox(world, currentBlockPos), ColorHolder(255, 255, 255))

            if (containerTask.taskState != TaskState.DONE) {
                addToRenderer(containerTask, currentTime)
            }

            pendingTasks.values.forEach {
                if (it.taskState == TaskState.DONE) return@forEach
                addToRenderer(it, currentTime)
            }

            doneTasks.values.forEach {
                if (it.block == Blocks.AIR || it.isShulker) return@forEach
                if (it.toRemove) {
                    addToRenderer(it, currentTime, true)
                } else {
                    addToRenderer(it, currentTime)
                }
            }
            renderer.render(false)
        }

        listener<RenderOverlayEvent> {
            if (!showDebugRender) return@listener
            GlStateUtils.rescaleActual()

            if (containerTask.taskState != TaskState.DONE) updateOverlay(containerTask.blockPos, containerTask)
            pendingTasks.forEach { (pos, blockTask) ->
                updateOverlay(pos, blockTask)
            }
        }

        safeListener<TickEvent.ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.START) return@safeListener

            updateRenderer()
            updateFood()

            if (!rubberbandTimer.tick(rubberbandTimeout.toLong(), false) ||
                PauseProcess.isActive ||
                AutoObsidian.isActive() ||
                (world.difficulty == EnumDifficulty.PEACEFUL &&
                    player.dimension == 1 &&
                    @Suppress("UNNECESSARY_SAFE_CALL")
                    player.serverBrand?.contains("2b2t") == true
                    )) {
                refreshData()
                return@safeListener
            }

            if (!active) {
                active = true
                BaritoneUtils.primary?.pathingControlManager?.registerProcess(HighwayToolsProcess)
            } else {
                // Cant update at higher frequency
                if (runtimeMilliSeconds % 15000 == 0) {
                    connection.sendPacket(CPacketClientStatus(CPacketClientStatus.State.REQUEST_STATS))
                }
                runtimeMilliSeconds += 50
                updateDequeues()
            }

            runTasks()
            doPathing()

            doRotation()
        }
    }

    private fun SafeClientEvent.updateRenderer() {
        containerTask.aabb = world
            .getBlockState(containerTask.blockPos)
            .getSelectedBoundingBox(world, containerTask.blockPos)

        pendingTasks.forEach { (_, task) ->
            task.aabb = world
                .getBlockState(task.blockPos)
                .getSelectedBoundingBox(world, task.blockPos)
        }

        doneTasks.forEach { (_, task) ->
            task.aabb = world
                .getBlockState(task.blockPos)
                .getSelectedBoundingBox(world, task.blockPos)
        }
    }

    private fun updateOverlay(pos: BlockPos, blockTask: BlockTask) {
        GL11.glPushMatrix()
        val screenPos = ProjectionUtils.toScreenPos(pos.toVec3dCenter())
        GL11.glTranslated(screenPos.x, screenPos.y, 0.0)
        GL11.glScalef(textScale * 2.0f, textScale * 2.0f, 1.0f)

        val color = ColorHolder(255, 255, 255, 255)

        val debugInfos = mutableListOf<Pair<String, String>>()
        if (blockTask.sides > 0) debugInfos.add(Pair("Sides", "${blockTask.sides}"))
        if (blockTask != containerTask) {
            debugInfos.add(Pair("Distance", "%.2f".format(blockTask.eyeDistance)))
        } else {
            debugInfos.add(Pair("Item", "${blockTask.item.registryName}"))
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

    private fun SafeClientEvent.updateFood() {
        val currentFood = player.foodStats.foodLevel
        if (currentFood < 7.0) {
            sendChatMessage("$chatName Out of food, disabling")
            mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
        }
        if (currentFood != prevFood) {
            if (currentFood < prevFood) foodLoss++
            prevFood = currentFood
        }
    }

    private fun updateDequeues() {
        val removeTime = System.currentTimeMillis() - simpleMovingAverageRange * 1000L

        updateDeque(simpleMovingAveragePlaces, removeTime)
        updateDeque(simpleMovingAverageBreaks, removeTime)
        updateDeque(simpleMovingAverageDistance, removeTime)

        runBlocking {
            packetLimiterMutex.withLock {
                updateDeque(packetLimiter, System.currentTimeMillis() - 1000L)
            }
        }
    }

    private fun updateDeque(deque: ArrayDeque<Long>, removeTime: Long) {
        while (deque.isNotEmpty() && deque.first() < removeTime) {
            deque.removeFirst()
        }
    }

    private fun SafeClientEvent.doRotation() {
        if (rotateTimer.tick(20L, false)) return
        val rotation = lastHitVec?.let { getRotationTo(it) } ?: return

        when (interacting) {
            RotationMode.SPOOF -> {
                sendPlayerPacket {
                    rotate(rotation)
                }
            }
            RotationMode.VIEW_LOCK -> {
                player.rotationYaw = rotation.x
                player.rotationPitch = rotation.y
            }
            else -> {
                // RotationMode.OFF
            }
        }
    }

    private fun SafeClientEvent.refreshData(originPos: BlockPos = currentBlockPos) {
        val toRemove = LinkedList<BlockPos>()
        doneTasks.forEach { (pos, task) ->
            if (originPos.distanceTo(pos) > maxReach && !task.toRemove) {
                if (task.toRemove) {
                    toRemove.add(pos)
                } else {
                    task.toRemove = true
                    task.timestamp = System.currentTimeMillis()
                }
            }
        }
        toRemove.forEach {
            doneTasks.remove(it)
        }

        lastTask = null
        moveState = MovementState.RUNNING

        generateBluePrint(originPos)

        blueprint.forEach { (pos, block) ->
            if (!(pos == containerTask.blockPos && containerTask.taskState == TaskState.DONE) ||
                startingBlockPos.add(
                    startingDirection
                        .clockwise(4)
                        .directionVec
                        .multiply(maxReach.ceilToInt())
                ).distanceTo(pos) < maxReach) {
                if (block == Blocks.AIR) {
                    addTaskClear(pos, originPos)
                } else {
                    addTaskBuild(pos, block, originPos)
                }
            }
        }
    }

    private fun SafeClientEvent.addTaskBuild(pos: BlockPos, block: Block, originPos: BlockPos) {
        val blockState = world.getBlockState(pos)

        when {
            blockState.block == block && originPos.distanceTo(pos) < maxReach -> {
                addTaskToDone(pos, block)
            }
            world.isPlaceable(pos, true) -> {
                if (originPos.distanceTo(pos) < maxReach - 1) {
                    if (checkSupport(pos, block)) {
                        addTaskToDone(pos, block)
                    } else {
                        addTaskToPending(pos, TaskState.PLACE, block)
                    }
                }
            }
            else -> {
                if (originPos.distanceTo(pos) < maxReach) {
                    if (checkSupport(pos, block)) {
                        addTaskToDone(pos, block)
                    } else {
                        addTaskToPending(pos, TaskState.BREAK, block)
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.checkSupport(pos: BlockPos, block: Block): Boolean {
        return mode == Mode.HIGHWAY &&
            startingDirection.isDiagonal &&
            world.getBlockState(pos.up()).block == material &&
            block == fillerMat
    }

    private fun SafeClientEvent.addTaskClear(pos: BlockPos, originPos: BlockPos) {
        when {
            originPos.distanceTo(pos) > maxReach -> {
                //
            }
            world.isAirBlock(pos) -> {
                addTaskToDone(pos, Blocks.AIR)
            }
            ignoreBlocks.contains(world.getBlockState(pos).block.registryName.toString()) -> {
                addTaskToDone(pos, world.getBlockState(pos).block)
            }
            else -> {
                addTaskToPending(pos, TaskState.BREAK, Blocks.AIR)
            }
        }
    }

    private fun generateBluePrint(feetPos: BlockPos) {
        blueprint.clear()
        val basePos = feetPos.down()

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
                        if (cleanWalls) generateWalls(thisPos, xDirection)
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
            blueprint[basePos.add(xDirection.directionVec.multiply(-1 - width / 2)).up(h + 1)] = fillerMat
            blueprint[basePos.add(xDirection.directionVec.multiply(width - width / 2)).up(h + 1)] = fillerMat
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

                if (startingBlockPos.distanceTo(pos) < startingBlockPos.distanceTo(currentBlockPos)) {
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

    private fun addTaskToPending(blockPos: BlockPos, taskState: TaskState, material: Block) {
        pendingTasks[blockPos]?.let {
            if (it.taskState != taskState || it.stuckTicks > it.taskState.stuckTimeout) {
                pendingTasks[blockPos] = (BlockTask(blockPos, taskState, material))
            }
        } ?: run {
            pendingTasks[blockPos] = (BlockTask(blockPos, taskState, material))
        }
    }

    private fun addTaskToDone(blockPos: BlockPos, material: Block) {
        doneTasks[blockPos]?.let {
            if (it.taskState != TaskState.DONE) {
                doneTasks[blockPos] = (BlockTask(blockPos, TaskState.DONE, material))
            }
        } ?: run {
            doneTasks[blockPos] = (BlockTask(blockPos, TaskState.DONE, material))
        }
    }

    private fun SafeClientEvent.doPathing() {
        when (moveState) {
            MovementState.RUNNING -> {
                if (grindCycles == 0) {
                    val nextPos = getNextPos()

                    if (currentBlockPos.distanceTo(targetBlockPos) < 2 ||
                        (distancePending > 0 && startingBlockPos.add(startingDirection.directionVec.multiply(distancePending)).distanceTo(currentBlockPos) == 0.0)) {
                        sendChatMessage("$chatName Reached target destination")
                        mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                        disable()
                        return
                    }

                    if (player.distanceTo(nextPos) < 2) {
                        currentBlockPos = nextPos
                    }

                    goal = GoalNear(nextPos, 0)
                }
            }
            MovementState.PICKUP -> {
                val droppedItemPos = getCollectingPosition()
                goal = if (droppedItemPos != null) {
                    GoalNear(droppedItemPos, 0)
                } else {
                    null
                }
            }
            MovementState.BRIDGE -> {
                // Bridge update
            }
        }
    }

    private fun SafeClientEvent.getNextPos(): BlockPos {
        var nextPos = currentBlockPos

        val possiblePos = currentBlockPos.add(startingDirection.directionVec)

        if (!isTaskDone(possiblePos) ||
            !isTaskDone(possiblePos.up()) ||
            !isTaskDone(possiblePos.down())) return nextPos

        if (checkTasks(possiblePos.up())) nextPos = possiblePos

        if (currentBlockPos != nextPos) {
            simpleMovingAverageDistance.add(System.currentTimeMillis())
            refreshData()
        }

        return nextPos
    }

    private fun SafeClientEvent.isTaskDone(pos: BlockPos) =
        (pendingTasks[pos] ?: doneTasks[pos])?.let {
            it.taskState == TaskState.DONE && world.getBlockState(pos).block != Blocks.PORTAL
        } ?: false

    private fun checkTasks(pos: BlockPos): Boolean {
        return pendingTasks.values.all {
            it.taskState == TaskState.DONE || pos.distanceTo(it.blockPos) < maxReach - 0.7
        }
    }

    private fun SafeClientEvent.runTasks() {
        if (player.inventory.isEmpty) return
        when {
            containerTask.taskState != TaskState.DONE -> {
                checkStuckTimeout(containerTask)
                pendingTasks.values.toList().forEach {
                    doTask(it, true)
                }
                doTask(containerTask, false)
            }
            grindCycles > 0 -> {
                if (player.inventorySlots.countItem(Items.DIAMOND_PICKAXE) > saveTools) {
                    handleRestock(material.item)
                } else {
                    handleRestock(Items.DIAMOND_PICKAXE)
                }
            }
            pendingTasks.isEmpty() -> {
                refreshData()
            }
            else -> {
                waitTicks--

                pendingTasks.values.toList().forEach {
                    doTask(it, true)
                }

                sortTasks()

                for (task in sortedTasks) {
                    if (!checkStuckTimeout(task)) return
                    if (task.taskState != TaskState.DONE && waitTicks > 0) return

                    doTask(task, false)

                    when (task.taskState) {
                        TaskState.DONE, TaskState.BROKEN, TaskState.PLACED -> {
                            continue
                        }
                        else -> {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.sortTasks() {

        if (multiBuilding) {
            pendingTasks.values.forEach {
                it.shuffle()
            }

            runBlocking {
                stateUpdateMutex.withLock {
                    sortedTasks = pendingTasks.values.sortedWith(
                        compareBy<BlockTask> {
                            it.taskState.ordinal
                        }.thenBy {
                            it.stuckTicks
                        }.thenBy {
                            it.shuffle
                        }
                    )
                }
            }
        } else {
            val eyePos = player.getPositionEyes(1.0f)

            pendingTasks.values.forEach {
                it.prepareSortInfo(this, eyePos)
            }

            runBlocking {
                stateUpdateMutex.withLock {
                    sortedTasks = pendingTasks.values.sortedWith(
                        compareBy<BlockTask> {
                            it.taskState.ordinal
                        }.thenBy {
                            it.stuckTicks
                        }.thenByDescending {
                            it.sides
                        }.thenBy {
                            it.startDistance
                        }.thenBy {
                            it.eyeDistance
                        }
                    )
                }
            }
        }
    }

    private fun SafeClientEvent.checkStuckTimeout(blockTask: BlockTask): Boolean {
        val timeout = blockTask.taskState.stuckTimeout

        if (blockTask.stuckTicks > timeout) {
            when (blockTask.taskState) {
                TaskState.PENDING_BREAK -> {
                    blockTask.updateState(TaskState.BREAK)
                }
                TaskState.PENDING_PLACE -> {
                    blockTask.updateState(TaskState.PLACE)
                }
                else -> {
                    if (debugMessages != DebugMessages.OFF) {
                        if (!anonymizeStats) {
                            sendChatMessage("$chatName Stuck while ${blockTask.taskState}@(${blockTask.blockPos.asString()}) for more than $timeout ticks (${blockTask.stuckTicks}), refreshing data.")
                        } else {
                            sendChatMessage("$chatName Stuck while ${blockTask.taskState} for more than $timeout ticks (${blockTask.stuckTicks}), refreshing data.")
                        }
                    }

                    when (blockTask.taskState) {
                        TaskState.PLACE -> {
                            if (dynamicDelay && extraPlaceDelay < 10) extraPlaceDelay += 1
                            getNeighbourSequence(blockTask.blockPos, placementSearch, maxReach, !illegalPlacements).firstOrNull()?.let {
                                playerController.processRightClickBlock(player, world, it.pos, it.side, it.hitVec, EnumHand.MAIN_HAND)
                            }
                            blockTask.updateState(TaskState.PLACED)
                        }
                        TaskState.PICKUP -> {
                            sendChatMessage("$chatName Can't pickup ${containerTask.item.registryName}@(${containerTask.blockPos.asString()})")
                            blockTask.updateState(TaskState.DONE)
                        }
                        else -> {
                            blockTask.updateState(TaskState.DONE)
                        }
                    }

                    refreshData()
                    return false
                }
            }
        }

        return true
    }

    private fun SafeClientEvent.doTask(blockTask: BlockTask, updateOnly: Boolean) {
        if (!updateOnly) blockTask.onTick()

        when (blockTask.taskState) {
            TaskState.DONE -> {
                doDone(blockTask)
            }
            TaskState.RESTOCK -> {
                doRestock()
            }
            TaskState.PICKUP -> {
                doPickup()
            }
            TaskState.OPEN_CONTAINER -> {
                doOpenContainer()
            }
            TaskState.BREAKING -> {
                doBreaking(blockTask, updateOnly)
            }
            TaskState.BROKEN -> {
                doBroken(blockTask)
            }
            TaskState.PLACED -> {
                doPlaced(blockTask)
            }
            TaskState.BREAK -> {
                doBreak(blockTask, updateOnly)
            }
            TaskState.PLACE, TaskState.LIQUID_SOURCE, TaskState.LIQUID_FLOW -> {
                doPlace(blockTask, updateOnly)
            }
            TaskState.PENDING_BREAK, TaskState.PENDING_PLACE -> {
                blockTask.onStuck()
            }
        }
    }

    private fun doDone(blockTask: BlockTask) {
        pendingTasks.remove(blockTask.blockPos)
        doneTasks[blockTask.blockPos] = blockTask
    }

    private fun SafeClientEvent.doRestock() {
        if (mc.currentScreen is GuiContainer && containerTask.isLoaded) {
            val container = player.openContainer

            container.getSlots(0..26).firstItem(containerTask.item)?.let {
                moveToInventory(it)
            } ?: run {
                getShulkerWith(container.getSlots(0..26), containerTask.item)?.let {
                    moveToInventory(it)
                } ?: run {
                    sendChatMessage("$chatName No ${containerTask.item.registryName} left in any container.")
                    mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f))
                    disable()
                    when (disableMode) {
                        DisableMode.ANTI_AFK -> {
                            sendChatMessage("$chatName Going into AFK mode.")
                            AntiAFK.enable()
                        }
                        DisableMode.LOGOUT -> {
                            sendChatMessage("$chatName CAUTION: Logging of in 1 minute!")
                            defaultScope.launch {
                                delay(6000L)
                                if (disableMode == DisableMode.LOGOUT && isEnabled) {
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
        } else {
            containerTask.updateState(TaskState.OPEN_CONTAINER)
        }
    }

    private fun SafeClientEvent.doPickup() {
        if (getCollectingPosition() == null) {
            moveState = MovementState.RUNNING
            containerTask.updateState(TaskState.DONE)
        } else {
            if (player.inventorySlots.firstEmpty() == null) {
                getEjectSlot()?.let {
                    throwAllInSlot(it)
                }
            } else {
                // ToDo: Resolve ghost slot
            }
            containerTask.onStuck()
        }
    }

    private fun SafeClientEvent.doOpenContainer() {
        if (containerTask.isOpen) {
            containerTask.updateState(TaskState.RESTOCK)
        } else {
            val center = containerTask.blockPos.toVec3dCenter()
            val diff = player.getPositionEyes(1f).subtract(center)
            val normalizedVec = diff.normalize()

            val side = EnumFacing.getFacingFromVector(normalizedVec.x.toFloat(), normalizedVec.y.toFloat(), normalizedVec.z.toFloat())
            val hitVecOffset = getHitVecOffset(side)

            lastHitVec = getHitVec(containerTask.blockPos, side)
            rotateTimer.reset()

            if (shulkerOpenTimer.tick(50)) {
                defaultScope.launch {
                    delay(20L)
                    onMainThreadSafe {
                        connection.sendPacket(CPacketPlayerTryUseItemOnBlock(containerTask.blockPos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat()))
                        player.swingArm(EnumHand.MAIN_HAND)
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.doBreaking(blockTask: BlockTask, updateOnly: Boolean) {
        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                waitTicks = breakDelay
                blockTask.updateState(TaskState.BROKEN)
                return
            }
            is BlockLiquid -> {
                updateLiquidTask(blockTask)
                return
            }
        }

        if (!updateOnly && swapOrMoveBestTool(blockTask)) {
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doBroken(blockTask: BlockTask) {
        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                totalBlocksBroken++
                if (blockTask.blockPos == prePrimedPos) {
                    primedPos = prePrimedPos
                    prePrimedPos = BlockPos.NULL_VECTOR
                }
                simpleMovingAverageBreaks.add(System.currentTimeMillis())

                when {
                    blockTask.block == Blocks.AIR -> {
                        if (fakeSounds) {
                            val soundType = blockTask.block.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                            world.playSound(player, blockTask.blockPos, soundType.breakSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                        }
                        blockTask.updateState(TaskState.DONE)
                    }
                    blockTask == containerTask -> {
                        if (containerTask.collect) {
                            moveState = MovementState.PICKUP
                            blockTask.updateState(TaskState.PICKUP)
                        } else {
                            blockTask.updateState(TaskState.DONE)
                        }
                    }
                    else -> {
                        blockTask.updateState(TaskState.PLACE)
                    }
                }
            }
            else -> {
                blockTask.updateState(TaskState.BREAK)
            }
        }
    }

    private fun SafeClientEvent.doPlaced(blockTask: BlockTask) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        when {
            blockTask.block == currentBlock && currentBlock != Blocks.AIR -> {
                totalBlocksPlaced++
                prePrimedPos = blockTask.blockPos
                simpleMovingAveragePlaces.add(System.currentTimeMillis())

                if (dynamicDelay && extraPlaceDelay > 0) extraPlaceDelay -= 1

                if (blockTask == containerTask) {
                    if (blockTask.destroy) {
                        blockTask.updateState(TaskState.BREAK)
                    } else {
                        blockTask.updateState(TaskState.RESTOCK)
                    }
                } else {
                    blockTask.updateState(TaskState.DONE)
                }
                if (fakeSounds) {
                    val soundType = currentBlock.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                    world.playSound(player, blockTask.blockPos, soundType.placeSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                }
            }
            blockTask.block == currentBlock && currentBlock == Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            blockTask.block == Blocks.AIR && currentBlock != Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            else -> {
                blockTask.updateState(TaskState.PLACE)
            }
        }
    }

    private fun SafeClientEvent.doBreak(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (ignoreBlocks.contains(currentBlock.registryName.toString()) &&
            !blockTask.isShulker &&
            !isInsideBlueprintBuild(blockTask.blockPos) ||
            currentBlock == Blocks.PORTAL ||
            currentBlock == Blocks.END_PORTAL ||
            currentBlock == Blocks.END_PORTAL_FRAME ||
            currentBlock == Blocks.BEDROCK) {
            blockTask.updateState(TaskState.DONE)
        }

        when (blockTask.block) {
            fillerMat -> {
                if (world.getBlockState(blockTask.blockPos.up()).block == material ||
                    (!world.isPlaceable(blockTask.blockPos) &&
                        world.getCollisionBox(blockTask.blockPos) != null)) {
                    blockTask.updateState(TaskState.DONE)
                    return
                }
            }
            material -> {
                if (currentBlock == material) {
                    blockTask.updateState(TaskState.DONE)
                    return
                }
            }
        }

        when (currentBlock) {
            Blocks.AIR -> {
                if (blockTask.block == Blocks.AIR) {
                    blockTask.updateState(TaskState.BROKEN)
                    return
                } else {
                    blockTask.updateState(TaskState.PLACE)
                    return
                }
            }
            is BlockLiquid -> {
                updateLiquidTask(blockTask)
                return
            }
        }

        if (!updateOnly && player.onGround && swapOrMoveBestTool(blockTask)) {
            if (handleLiquid(blockTask)) return
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doPlace(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (bridging && player.positionVector.distanceTo(currentBlockPos) < 1 && shouldBridge()) {
            val factor = if (startingDirection.isDiagonal) {
                0.555
            } else {
                0.505
            }
            val target = currentBlockPos.toVec3dCenter().add(Vec3d(startingDirection.directionVec).scale(factor))
            player.motionX = (target.x - player.posX).coerceIn(-0.2, 0.2)
            player.motionZ = (target.z - player.posZ).coerceIn(-0.2, 0.2)
        }

        if ((blockTask.taskState == TaskState.LIQUID_FLOW ||
                blockTask.taskState == TaskState.LIQUID_SOURCE) &&
            !world.isLiquid(blockTask.blockPos)) {
            blockTask.updateState(TaskState.DONE)
            return
        }

        when (blockTask.block) {
            material -> {
                if (currentBlock == material) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                } else if (currentBlock != Blocks.AIR && !world.isLiquid(blockTask.blockPos)) {
                    blockTask.updateState(TaskState.BREAK)
                    return
                }
            }
            fillerMat -> {
                if (currentBlock == fillerMat) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                } else if (currentBlock != fillerMat &&
                    mode == Mode.HIGHWAY &&
                    world.getBlockState(blockTask.blockPos.up()).block == material) {
                    blockTask.updateState(TaskState.DONE)
                    return
                }
            }
            Blocks.AIR -> {
                if (!world.isLiquid(blockTask.blockPos)) {
                    if (currentBlock != Blocks.AIR) {
                        blockTask.updateState(TaskState.BREAK)
                    } else {
                        blockTask.updateState(TaskState.BROKEN)
                    }
                    return
                }
            }
        }

        if (!updateOnly) {
            if (!world.isPlaceable(blockTask.blockPos)) {
                if (debugMessages == DebugMessages.ALL) {
                    if (!anonymizeStats) {
                        sendChatMessage("$chatName Invalid place position @(${blockTask.blockPos.asString()}) Removing task")
                    } else {
                        sendChatMessage("$chatName Invalid place position. Removing task")
                    }
                }

                if (blockTask == containerTask) {
                    if (containerTask.block == currentBlock) {
                        containerTask.updateState(TaskState.BREAK)
                    } else {
                        containerTask.updateState(TaskState.DONE)
                    }
                } else {
                    pendingTasks.remove(blockTask.blockPos)
                }
                return
            }

            if (!swapOrMoveBlock(blockTask)) {
                blockTask.onStuck()
                return
            }

            placeBlock(blockTask)
        }
    }

    private fun SafeClientEvent.placeBlock(blockTask: BlockTask) {
        val neighbours = getNeighbourSequence(blockTask.blockPos, placementSearch, maxReach, !illegalPlacements)

        when (neighbours.size) {
            0 -> {
                if (blockTask.taskState == TaskState.LIQUID_FLOW || blockTask.taskState == TaskState.LIQUID_SOURCE) {
                    if (debugMessages == DebugMessages.ALL) {
                        if (!anonymizeStats) {
                            sendChatMessage("$chatName Can't replace Liquid@(${blockTask.blockPos})")
                        } else {
                            sendChatMessage("$chatName Can't replace Liquid")
                        }
                    }
                    blockTask.updateState(TaskState.DONE)
                    return
                }
                if (debugMessages == DebugMessages.ALL) {
                    if (!anonymizeStats) {
                        sendChatMessage("$chatName No neighbours found for ${blockTask.blockPos}")
                    } else {
                        sendChatMessage("$chatName No neighbours found")
                    }
                }
                if (blockTask == containerTask) blockTask.updateState(TaskState.DONE)
                blockTask.onStuck(21)
                return
            }
            1 -> {
                val last = neighbours.last()
                lastHitVec = getHitVec(last.pos, last.side)
                rotateTimer.reset()

                placeBlockNormal(blockTask, last.pos, last.side)
            }
            else -> {
                neighbours.forEach {
                    addTaskToPending(it.pos, TaskState.PLACE, fillerMat)
                }
            }
        }
    }

    private fun SafeClientEvent.placeBlockNormal(blockTask: BlockTask, placePos: BlockPos, side: EnumFacing) {
        val hitVecOffset = getHitVecOffset(side)
        val currentBlock = world.getBlockState(placePos).block

        waitTicks = if (dynamicDelay) {
            placeDelay + extraPlaceDelay
        } else {
            placeDelay
        }
        blockTask.updateState(TaskState.PENDING_PLACE)

        if (currentBlock in blockBlacklist) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }

        defaultScope.launch {
            delay(20L)
            onMainThreadSafe {
                val placePacket = CPacketPlayerTryUseItemOnBlock(placePos, side, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
                connection.sendPacket(placePacket)
                player.swingArm(EnumHand.MAIN_HAND)
            }

            if (currentBlock in blockBlacklist) {
                delay(20L)
                onMainThreadSafe {
                    connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                }
            }

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_PLACE) {
                stateUpdateMutex.withLock {
                    blockTask.updateState(TaskState.PLACE)
                }
                if (dynamicDelay && extraPlaceDelay < 10) extraPlaceDelay += 1
            }
        }
    }

    private fun SafeClientEvent.mineBlock(blockTask: BlockTask) {
        val blockState = world.getBlockState(blockTask.blockPos)

        if (blockState.block == Blocks.FIRE) {
            val sides = getNeighbourSequence(blockTask.blockPos, 1, maxReach, true)
            if (sides.isEmpty()) {
                blockTask.updateState(TaskState.PLACE)
                return
            }

            lastHitVec = getHitVec(sides.last().pos, sides.last().side)
            rotateTimer.reset()

            mineBlockNormal(blockTask, sides.last().side)
        } else {
            var side = getMiningSide(blockTask.blockPos) ?: run {
                blockTask.onStuck()
                return
            }

            if (blockTask.blockPos == primedPos && instantMine) {
                side = side.opposite
            }
            lastHitVec = getHitVec(blockTask.blockPos, side)
            rotateTimer.reset()

            if (blockState.getPlayerRelativeBlockHardness(player, world, blockTask.blockPos) > 2.8) {
                mineBlockInstant(blockTask, side)
            } else {
                mineBlockNormal(blockTask, side)
            }
        }
    }

    private fun mineBlockInstant(blockTask: BlockTask, side: EnumFacing) {
        waitTicks = breakDelay
        blockTask.updateState(TaskState.PENDING_BREAK)

        defaultScope.launch {
            packetLimiterMutex.withLock {
                packetLimiter.add(System.currentTimeMillis())
            }

            delay(20L)
            sendMiningPackets(blockTask.blockPos, side)

            if (maxBreaks > 1) {
                tryMultiBreak(blockTask)
            }

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_BREAK) {
                stateUpdateMutex.withLock {
                    blockTask.updateState(TaskState.BREAK)
                }
            }
        }
    }

    private suspend fun tryMultiBreak(blockTask: BlockTask) {
        runSafeSuspend {
            val eyePos = player.getPositionEyes(1.0f)
            val viewVec = lastHitVec.subtract(eyePos).normalize()
            var breakCount = 1

            for (task in sortedTasks) {
                if (breakCount >= maxBreaks) break

                val size = packetLimiterMutex.withLock {
                    packetLimiter.size
                }

                val limit = when (limitOrigin) {
                    LimitMode.FIXED -> 20.0f
                    LimitMode.SERVER -> TpsCalculator.tickRate
                }

                if (size > limit * limitFactor) {
                    if (debugMessages == DebugMessages.ALL) {
                        sendChatMessage("$chatName Dropped possible instant mine action @ TPS($limit) Actions(${size})")
                    }
                    break
                }

                if (task == blockTask) continue
                if (task.taskState != TaskState.BREAK) continue
                if (world.getBlockState(task.blockPos).block != Blocks.NETHERRACK) continue

                val box = AxisAlignedBB(task.blockPos)
                val rayTraceResult = box.isInSight(eyePos, viewVec) ?: continue

                if (handleLiquid(task)) break

                breakCount++
                packetLimiterMutex.withLock {
                    packetLimiter.add(System.currentTimeMillis())
                }

                defaultScope.launch {
                    sendMiningPackets(task.blockPos, rayTraceResult.sideHit)

                    delay(50L * taskTimeout)
                    if (blockTask.taskState == TaskState.PENDING_BREAK) {
                        stateUpdateMutex.withLock {
                            blockTask.updateState(TaskState.BREAK)
                        }
                    }
                }
            }
        }
    }

    private fun mineBlockNormal(blockTask: BlockTask, side: EnumFacing) {
        if (blockTask.taskState == TaskState.BREAK) {
            blockTask.updateState(TaskState.BREAKING)
        }

        defaultScope.launch {
            delay(20L)
            sendMiningPackets(blockTask.blockPos, side)
        }
    }

    private suspend fun sendMiningPackets(pos: BlockPos, side: EnumFacing) {
        onMainThreadSafe {
            connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, side))
            connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, side))
            player.swingArm(EnumHand.MAIN_HAND)
        }
    }

    private fun SafeClientEvent.shouldBridge(): Boolean {
        return world.getBlockState(currentBlockPos.add(startingDirection.directionVec).down()).isReplaceable &&
            !sortedTasks.any {
                getNeighbourSequence(it.blockPos, placementSearch, maxReach, !illegalPlacements).isNotEmpty() &&
                    (it.taskState == TaskState.PLACE ||
                        it.taskState == TaskState.LIQUID_SOURCE ||
                        it.taskState == TaskState.LIQUID_FLOW)
            }
    }

    private fun SafeClientEvent.getBestTool(blockTask: BlockTask): Slot? {
        return player.inventorySlots.asReversed().maxByOrNull {
            val stack = it.stack
            if (stack.isEmpty) {
                0.0f
            } else {
                var speed = stack.getDestroySpeed(world.getBlockState(blockTask.blockPos))

                if (speed > 1.0f) {
                    val efficiency = EnchantmentHelper.getEnchantmentLevel(Enchantments.EFFICIENCY, stack)
                    if (efficiency > 0) {
                        speed += efficiency * efficiency + 1.0f
                    }
                }

                speed
            }
        }
    }

    private fun SafeClientEvent.swapOrMoveBlock(blockTask: BlockTask): Boolean {
        if (blockTask.isShulker) {
            getShulkerWith(player.inventorySlots, blockTask.item)?.let { slot ->
                blockTask.itemID = slot.stack.item.id
                slot.toHotbarSlotOrNull()?.let {
                    swapToSlot(it)
                } ?: run {
                    val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                    moveToHotbar(slot.slotNumber, slotTo)
                }
            }
            return true
        } else {
            if (storageManagement && grindObsidian &&
                containerTask.taskState == TaskState.DONE &&
                (player.inventorySlots.countBlock(material) <= saveMaterial &&
                    grindCycles == 0)) {
                grindCycles = player.inventorySlots.count { it.stack.isEmpty || InventoryManager.ejectList.contains(it.stack.item.registryName.toString()) } * 8 - 1
                return false
            }

            val useBlock = when {
                player.inventorySlots.countBlock(blockTask.block) > 0 -> blockTask.block
                player.inventorySlots.countBlock(material) > 0 -> material
                player.inventorySlots.countBlock(fillerMat) > 0 && mode == Mode.TUNNEL -> fillerMat
                else -> blockTask.block
            }

            val success = swapToBlockOrMove(useBlock, predicateSlot = {
                it.item is ItemBlock
            })

            return if (!success) {
                sendChatMessage("$chatName No ${blockTask.block.localizedName} was found in inventory")
                mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                disable()
                false
            } else {
                true
            }
        }
    }

    private fun SafeClientEvent.swapOrMoveBestTool(blockTask: BlockTask): Boolean {
        if (player.inventorySlots.countItem(Items.DIAMOND_PICKAXE) <= saveTools) {
            return if (containerTask.taskState == TaskState.DONE && storageManagement) {
                handleRestock(Items.DIAMOND_PICKAXE)
                false
            } else {
                swapOrMoveTool(blockTask)
            }
        }

        return swapOrMoveTool(blockTask)
    }

    private fun SafeClientEvent.swapOrMoveTool(blockTask: BlockTask) =
        getBestTool(blockTask)?.let { slotFrom ->
            slotFrom.toHotbarSlotOrNull()?.let {
                swapToSlot(it)
            } ?: run {
                val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                moveToHotbar(slotFrom.slotNumber, slotTo)
            }
            true
        } ?: run {
            false
        }

    private fun SafeClientEvent.handleRestock(item: Item) {
        getShulkerWith(player.inventorySlots, item)?.let { slot ->
            getRemotePos()?.let { pos ->
                containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, item)
                containerTask.isShulker = true
            } ?: run {
                disableNoPosition(1)
            }
        } ?: run {
            if (item.block == Blocks.OBSIDIAN) {
                if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) <= saveEnder) {
                    getShulkerWith(player.inventorySlots, Blocks.ENDER_CHEST.item)?.let { slot ->
                        getRemotePos()?.let { pos ->
                            containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, Blocks.ENDER_CHEST.item)
                            containerTask.isShulker = true
                        } ?: run {
                            disableNoPosition(2)
                        }
                    } ?: run {
                        dispatchEnderChest(Blocks.ENDER_CHEST.item)
                    }
                } else {
                    getRemotePos()?.let { pos ->
                        containerTask = BlockTask(pos, TaskState.PLACE, Blocks.ENDER_CHEST)
                        containerTask.destroy = true
                        if (grindCycles > 1) containerTask.collect = false
                        containerTask.itemID = Blocks.OBSIDIAN.id
                        grindCycles--
                    } ?: run {
                        disableNoPosition(3)
                    }
                }
            } else {
                dispatchEnderChest(item)
            }
        }
    }

    private fun SafeClientEvent.dispatchEnderChest(item: Item) {
        if (player.inventorySlots.countBlock(Blocks.ENDER_CHEST) > 0) {
            getRemotePos()?.let { pos ->
                containerTask = BlockTask(pos, TaskState.PLACE, Blocks.ENDER_CHEST, item)
                containerTask.itemID = Blocks.OBSIDIAN.id
            } ?: run {
                disableNoPosition(4)
            }
        } else {
            getShulkerWith(player.inventorySlots, Blocks.ENDER_CHEST.item)?.let { slot ->
                getRemotePos()?.let { pos ->
                    containerTask = BlockTask(pos, TaskState.PLACE, slot.stack.item.block, Blocks.ENDER_CHEST.item)
                    containerTask.isShulker = true
                } ?: run {
                    disableNoPosition(5)
                }
            } ?: run {
                sendChatMessage("$chatName No ${Blocks.ENDER_CHEST.item.registryName} was found in inventory.")
                mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f))
                disable()
            }
        }
    }

    private fun disableNoPosition(id: Int) {
        sendChatMessage("$chatName Can't find possible container position. ($id)")
        mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f))
        disable()
    }

    private fun SafeClientEvent.getRemotePos(): BlockPos? {
        val origin = currentBlockPos.up().toVec3dCenter()

        return VectorUtils.getBlockPosInSphere(origin, maxReach).asSequence()
            .filter { pos ->
                !isInsideBlueprintBuild(pos) &&
                    pos != currentBlockPos &&
                    world.isPlaceable(pos) &&
                    !world.getBlockState(pos.down()).isReplaceable &&
                    world.isAirBlock(pos.up()) &&
                    world.rayTraceBlocks(origin, pos.toVec3dCenter())?.let { it.typeOfHit == RayTraceResult.Type.MISS } ?: true
            }
            .sortedWith(
                compareBy<BlockPos> {
                    it.distanceSqToCenter(origin.x, origin.y, origin.z).ceilToInt()
                }.thenBy {
                    it.y
                }
            ).firstOrNull()
    }

    private fun getShulkerWith(slots: List<Slot>, item: Item): Slot? {
        return slots.filter {
            it.stack.item is ItemShulkerBox && getShulkerData(it.stack, item) > 0
        }.minByOrNull {
            getShulkerData(it.stack, item)
        }
    }

    private fun SafeClientEvent.moveToInventory(slot: Slot) {
        player.openContainer.getSlots(27..62).firstOrNull {
            (slot.stack.item == it.stack.item && it.stack.count < slot.slotStackLimit - slot.stack.count) ||
                slot.stack.item == Items.AIR
        }?.let {
            clickSlot(player.openContainer.windowId, slot, 0, ClickType.QUICK_MOVE)
        } ?: run {
            player.hotbarSlots.firstOrNull {
                InventoryManager.ejectList.contains(it.stack.item.registryName.toString())
            }?.let {
                clickSlot(player.openContainer.windowId, slot, it.hotbarSlot, ClickType.SWAP)
            } ?: run {
                sendChatMessage("To be implemented")
            }
        }

        if (leaveEmptyShulkers &&
            player.openContainer.getSlots(0..26).all { it.stack.isEmpty || InventoryManager.ejectList.contains(it.stack.item.registryName.toString()) }) {
            if (debugMessages != DebugMessages.OFF) {
                if (!anonymizeStats) {
                    sendChatMessage("$chatName Left empty ${containerTask.block.localizedName}@(${containerTask.blockPos.asString()})")
                } else {
                    sendChatMessage("$chatName Left empty ${containerTask.block.localizedName}")
                }
            }
            containerTask.updateState(TaskState.DONE)
        } else {
            containerTask.updateState(TaskState.BREAK)
        }

        containerTask.isOpen = false
        player.closeScreen()
    }

    private fun getShulkerData(stack: ItemStack, item: Item): Int {
        val tagCompound = if (stack.item is ItemShulkerBox) stack.tagCompound else return 0

        if (tagCompound != null && tagCompound.hasKey("BlockEntityTag", 10)) {
            val blockEntityTag = tagCompound.getCompoundTag("BlockEntityTag")
            if (blockEntityTag.hasKey("Items", 9)) {
                val shulkerInventory = NonNullList.withSize(27, ItemStack.EMPTY)
                ItemStackHelper.loadAllItems(blockEntityTag, shulkerInventory)
                return shulkerInventory.count { it.item == item }
            }
        }

        return 0
    }

    private fun SafeClientEvent.getCollectingPosition(): BlockPos? {
        val range = 8f
        getDroppedItems(containerTask.itemID, range = range)
            .minByOrNull { player.getDistance(it) }
            ?.positionVector
            ?.let { itemVec ->
                return VectorUtils.getBlockPosInSphere(itemVec, range).asSequence()
                    .filter { pos ->
                        world.isAirBlock(pos.up()) &&
                            world.isAirBlock(pos) &&
                            !world.isPlaceable(pos.down())
                    }
                    .sortedWith(
                        compareBy<BlockPos> {
                            it.distanceSqToCenter(itemVec.x, itemVec.y, itemVec.z)
                        }.thenBy {
                            it.y
                        }
                    ).firstOrNull()
            }
        return null
    }

    private fun SafeClientEvent.getEjectSlot(): Slot? {
        return player.inventorySlots.firstByStack {
            !it.isEmpty &&
                InventoryManager.ejectList.contains(it.item.registryName.toString())
        }
    }

    private fun SafeClientEvent.handleLiquid(blockTask: BlockTask): Boolean {
        var foundLiquid = false

        for (side in EnumFacing.values()) {
            if (side == EnumFacing.DOWN) continue
            val neighbourPos = blockTask.blockPos.offset(side)

            if (world.getBlockState(neighbourPos).block !is BlockLiquid) continue

            if (player.distanceTo(neighbourPos) > maxReach) {
                blockTask.updateState(TaskState.DONE)
                if (debugMessages == DebugMessages.ALL) {
                    if (!anonymizeStats) {
                        sendChatMessage("$chatName Liquid@(${neighbourPos.asString()}) out of reach (${player.distanceTo(neighbourPos)})")
                    } else {
                        sendChatMessage("$chatName Liquid out of reach (${player.distanceTo(neighbourPos)})")
                    }
                }
                return true
            }

            foundLiquid = true

            val isFlowing = world.getBlockState(blockTask.blockPos).let {
                it.block is BlockLiquid && it.getValue(BlockLiquid.LEVEL) != 0
            }

            val filler = if (isInsideBlueprintBuild(neighbourPos) && mode == Mode.HIGHWAY) material else fillerMat

            pendingTasks[neighbourPos]?.let {
                if (isFlowing) {
                    it.updateState(TaskState.LIQUID_FLOW)
                } else {
                    it.updateState(TaskState.LIQUID_FLOW)
                }

                it.updateMaterial(filler)
            } ?: run {
                if (isFlowing) {
                    addTaskToPending(neighbourPos, TaskState.LIQUID_FLOW, filler)
                } else {
                    addTaskToPending(neighbourPos, TaskState.LIQUID_SOURCE, filler)
                }
            }
        }

        return foundLiquid
    }

    private fun SafeClientEvent.updateLiquidTask(blockTask: BlockTask) {
        val filler = if (player.inventorySlots.countBlock(fillerMat) == 0 ||
            (isInsideBlueprintBuild(blockTask.blockPos) &&
                mode == Mode.HIGHWAY)) {
            material
        } else {
            fillerMat
        }

        if (world.getBlockState(blockTask.blockPos).getValue(BlockLiquid.LEVEL) != 0) {
            blockTask.updateState(TaskState.LIQUID_FLOW)
            blockTask.updateMaterial(filler)
        } else {
            blockTask.updateState(TaskState.LIQUID_SOURCE)
            blockTask.updateMaterial(filler)
        }
    }

    private fun isInsideBlueprint(pos: BlockPos): Boolean {
        return blueprint.containsKey(pos)
    }

    private fun isInsideBlueprintBuild(pos: BlockPos): Boolean {
        val mat = when (mode) {
            Mode.HIGHWAY, Mode.FLAT -> material
            Mode.TUNNEL -> fillerMat
        }
        return blueprint[pos]?.let { it == mat } ?: false
    }

    fun printSettings() {
        StringBuilder(ignoreBlocks.size + 1).run {
            append("$chatName Settings" +
                "\n §9> §rMain material: §7${material.localizedName}" +
                "\n §9> §rFiller material: §7${fillerMat.localizedName}" +
                "\n §9> §rIgnored Blocks:")

            ignoreBlocks.forEach {
                append("\n     §9> §7$it")
            }

            sendChatMessage(toString())
        }
    }

    fun SafeClientEvent.gatherStatistics(displayText: TextComponent) {
        val runtimeSec = (runtimeMilliSeconds / 1000) + 0.0001
        val distanceDone = startingBlockPos.distanceTo(currentBlockPos).toInt() + totalDistance

        if (showSession) gatherSession(displayText, runtimeSec)

        if (showLifeTime) gatherLifeTime(displayText)

        if (showPerformance) gatherPerformance(displayText, runtimeSec, distanceDone)

        if (showEnvironment) gatherEnvironment(displayText)

        if (showTask) gatherTask(displayText)

        if (showEstimations) gatherEstimations(displayText, runtimeSec, distanceDone)

        if (printDebug) {
            if (containerTask.taskState != TaskState.DONE) {
                displayText.addLine("Container", primaryColor, scale = 0.6f)
                displayText.addLine(containerTask.prettyPrint(), primaryColor, scale = 0.6f)
            }

            if (sortedTasks.isNotEmpty()) {
                displayText.addLine("Pending", primaryColor, scale = 0.6f)
                addTaskComponentList(displayText, sortedTasks)
            }

            if (sortedTasks.isNotEmpty()) {
                displayText.addLine("Done", primaryColor, scale = 0.6f)
                addTaskComponentList(displayText, doneTasks.values)
            }
        }

        displayText.addLine("by Constructor#9948/Avanatiker", primaryColor, scale = 0.6f)
    }

    private fun gatherSession(displayText: TextComponent, runtimeSec: Double) {
        val seconds = (runtimeSec % 60.0).toInt().toString().padStart(2, '0')
        val minutes = ((runtimeSec % 3600.0) / 60.0).toInt().toString().padStart(2, '0')
        val hours = (runtimeSec / 3600.0).toInt().toString().padStart(2, '0')

        displayText.addLine("Session", primaryColor)

        displayText.add("    Runtime:", primaryColor)
        displayText.addLine("$hours:$minutes:$seconds", secondaryColor)

        displayText.add("    Direction:", primaryColor)
        displayText.addLine("${startingDirection.displayName} / ${startingDirection.displayNameXY}", secondaryColor)

        if (!anonymizeStats) displayText.add("    Start:", primaryColor)
        if (!anonymizeStats) displayText.addLine("(${startingBlockPos.asString()})", secondaryColor)

        displayText.add("    Session placed / destroyed:", primaryColor)
        displayText.addLine("%,d".format(totalBlocksPlaced) + " / " + "%,d".format(totalBlocksBroken), secondaryColor)


    }

    private fun SafeClientEvent.gatherLifeTime(displayText: TextComponent) {
        matPlaced = StatList.getObjectUseStats(material.item)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0
        enderMined = StatList.getBlockStats(Blocks.ENDER_CHEST)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0
        netherrackMined = StatList.getBlockStats(Blocks.NETHERRACK)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0
        pickaxeBroken = StatList.getObjectBreakStats(Items.DIAMOND_PICKAXE)?.let {
            player.statFileWriter.readStat(it)
        } ?: 0

        if (matPlaced + enderMined + netherrackMined + pickaxeBroken > 0) {
            displayText.addLine("Lifetime", primaryColor)
        }

        if (mode == Mode.HIGHWAY || mode == Mode.FLAT) {
            if (matPlaced > 0) {
                displayText.add("    ${material.localizedName} placed:", primaryColor)
                displayText.addLine("%,d".format(matPlaced), secondaryColor)
            }

            if (enderMined > 0) {
                displayText.add("    ${Blocks.ENDER_CHEST.localizedName} mined:", primaryColor)
                displayText.addLine("%,d".format(enderMined), secondaryColor)
            }
        }

        if (netherrackMined > 0) {
            displayText.add("    ${Blocks.NETHERRACK.localizedName} mined:", primaryColor)
            displayText.addLine("%,d".format(netherrackMined), secondaryColor)
        }

        if (pickaxeBroken > 0) {
            displayText.add("    Diamond Pickaxe broken:", primaryColor)
            displayText.addLine("%,d".format(pickaxeBroken), secondaryColor)
        }
    }

    private fun gatherPerformance(displayText: TextComponent, runtimeSec: Double, distanceDone: Double) {
        displayText.addLine("Performance", primaryColor)

        displayText.add("    Placements / s: ", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format(totalBlocksPlaced / runtimeSec, simpleMovingAveragePlaces.size / simpleMovingAverageRange.toDouble()), secondaryColor)

        displayText.add("    Breaks / s:", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format(totalBlocksBroken / runtimeSec, simpleMovingAverageBreaks.size / simpleMovingAverageRange.toDouble()), secondaryColor)

        displayText.add("    Distance km / h:", primaryColor)
        displayText.addLine("%.3f SMA(%.3f)".format((distanceDone / runtimeSec * 60.0 * 60.0) / 1000.0, (simpleMovingAverageDistance.size / simpleMovingAverageRange * 60.0 * 60.0) / 1000.0), secondaryColor)

        displayText.add("    Food level loss / h:", primaryColor)
        displayText.addLine("%.2f".format(totalBlocksBroken / foodLoss.toDouble()), secondaryColor)

        displayText.add("    Pickaxes / h:", primaryColor)
        displayText.addLine("%.2f".format((durabilityUsages / runtimeSec) * 60.0 * 60.0 / 1561.0), secondaryColor)
    }

    private fun gatherEnvironment(displayText: TextComponent) {
        displayText.addLine("Environment", primaryColor)

        displayText.add("    Materials:", primaryColor)
        displayText.addLine("Main(${material.localizedName}) Filler(${fillerMat.localizedName})", secondaryColor)

        displayText.add("    Dimensions:", primaryColor)
        displayText.addLine("Width($width) Height($height)", secondaryColor)

        displayText.add("    Delays:", primaryColor)
        if (dynamicDelay) {
            displayText.addLine("Place(${placeDelay + extraPlaceDelay}) Break($breakDelay)", secondaryColor)
        } else {
            displayText.addLine("Place($placeDelay) Break($breakDelay)", secondaryColor)
        }

        displayText.add("    Movement:", primaryColor)
        displayText.addLine("$moveState", secondaryColor)
    }

    private fun gatherTask(displayText: TextComponent) {
        sortedTasks.firstOrNull()?.let {
            displayText.addLine("Task", primaryColor)

            displayText.add("    Status:", primaryColor)
            displayText.addLine("${it.taskState}", secondaryColor)

            displayText.add("    Target block:", primaryColor)
            displayText.addLine(it.block.localizedName, secondaryColor)

            if (!anonymizeStats) displayText.add("    Position:", primaryColor)
            if (!anonymizeStats) displayText.addLine("(${it.blockPos.asString()})", secondaryColor)

            displayText.add("    Ticks stuck:", primaryColor)
            displayText.addLine("${it.stuckTicks}", secondaryColor)
        }
    }

    private fun SafeClientEvent.gatherEstimations(displayText: TextComponent, runtimeSec: Double, distanceDone: Double) {
        when (mode) {
            Mode.HIGHWAY, Mode.FLAT -> {
                materialLeft = player.inventorySlots.countBlock(material)
                fillerMatLeft = player.inventorySlots.countBlock(fillerMat)
                val indirectMaterialLeft = 8 * player.inventorySlots.countBlock(Blocks.ENDER_CHEST)

                val pavingLeft = materialLeft / (totalBlocksPlaced.coerceAtLeast(1) / distanceDone.coerceAtLeast(1.0))

                // ToDo: Cache shulker count

//                  val pavingLeftAll = (materialLeft + indirectMaterialLeft) / ((totalBlocksPlaced + 0.001) / (distanceDone + 0.001))

                val secLeft = (pavingLeft).coerceAtLeast(0.0) / (startingBlockPos.distanceTo(currentBlockPos).toInt() / runtimeSec)
                val secondsLeft = (secLeft % 60).toInt().toString().padStart(2, '0')
                val minutesLeft = ((secLeft % 3600) / 60).toInt().toString().padStart(2, '0')
                val hoursLeft = (secLeft / 3600).toInt().toString().padStart(2, '0')

                displayText.addLine("Refill", primaryColor)
                displayText.add("    ${material.localizedName}:", primaryColor)

                if (material == Blocks.OBSIDIAN) {
                    displayText.addLine("Direct($materialLeft) Indirect($indirectMaterialLeft)", secondaryColor)
                } else {
                    displayText.addLine("$materialLeft", secondaryColor)
                }

                displayText.add("    ${fillerMat.localizedName}:", primaryColor)
                displayText.addLine("$fillerMatLeft", secondaryColor)

                if (grindCycles > 0) {
                    displayText.add("    Ender Chest cycles:", primaryColor)
                    displayText.addLine("$grindCycles", secondaryColor)
                } else {
                    displayText.add("    Distance left:", primaryColor)
                    displayText.addLine("${pavingLeft.toInt()}", secondaryColor)

                    if (!anonymizeStats) displayText.add("    Destination:", primaryColor)
                    if (!anonymizeStats) displayText.addLine("(${currentBlockPos.add(startingDirection.directionVec.multiply(pavingLeft.toInt())).asString()})", secondaryColor)

                    displayText.add("    ETA:", primaryColor)
                    displayText.addLine("$hoursLeft:$minutesLeft:$secondsLeft", secondaryColor)
                }
            }
            Mode.TUNNEL -> {
                val pickaxesLeft = player.inventorySlots.countItem<ItemPickaxe>()

                val tunnelingLeft = (pickaxesLeft * 1561) / (durabilityUsages.coerceAtLeast(1) / distanceDone.coerceAtLeast(1.0))

                val secLeft = tunnelingLeft.coerceAtLeast(0.0) / (startingBlockPos.distanceTo(currentBlockPos).toInt() / runtimeSec)
                val secondsLeft = (secLeft % 60).toInt().toString().padStart(2, '0')
                val minutesLeft = ((secLeft % 3600) / 60).toInt().toString().padStart(2, '0')
                val hoursLeft = (secLeft / 3600).toInt().toString().padStart(2, '0')

                displayText.addLine("Destination:", primaryColor)

                displayText.add("    Pickaxes:", primaryColor)
                displayText.addLine("$pickaxesLeft", secondaryColor)

                displayText.add("    Distance left:", primaryColor)
                displayText.addLine("${tunnelingLeft.toInt()}", secondaryColor)

                if (!anonymizeStats) displayText.add("    Destination:", primaryColor)
                if (!anonymizeStats) displayText.addLine("(${currentBlockPos.add(startingDirection.directionVec.multiply(tunnelingLeft.toInt())).asString()})", secondaryColor)

                displayText.add("    ETA:", primaryColor)
                displayText.addLine("$hoursLeft:$minutesLeft:$secondsLeft", secondaryColor)
            }
        }
    }

    private fun resetStats() {
        simpleMovingAveragePlaces.clear()
        simpleMovingAverageBreaks.clear()
        simpleMovingAverageDistance.clear()
        totalBlocksPlaced = 0
        totalBlocksBroken = 0
        totalDistance = 0.0
        runtimeMilliSeconds = 0
        prevFood = 0
        foodLoss = 1
        materialLeft = 0
        fillerMatLeft = 0
        lastToolDamage = 0
        durabilityUsages = 0
    }

    private fun addTaskComponentList(displayText: TextComponent, tasks: Collection<BlockTask>) {
        tasks.forEach {
            displayText.addLine(it.prettyPrint(), primaryColor, scale = 0.6f)
        }
    }

    class BlockTask(
        val blockPos: BlockPos,
        var taskState: TaskState,
        var block: Block,
        var item: Item = Items.AIR
    ) {
        private var ranTicks = 0
        var stuckTicks = 0; private set
        var shuffle = 0; private set
        var sides = 0; private set
        var startDistance = 0.0; private set
        var eyeDistance = 0.0; private set

        var isShulker = false
        var isOpen = false
        var isLoaded = false
        var itemID = 0
        var destroy = false
        var collect = true

//      var isBridge = false ToDo: Implement

        var timestamp = System.currentTimeMillis()
        var aabb = AxisAlignedBB(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)

        var toRemove = false

        fun updateState(state: TaskState) {
            if (state == taskState) return

            taskState = state
            timestamp = System.currentTimeMillis()
            if (state == TaskState.DONE || state == TaskState.PLACED || state == TaskState.BROKEN) {
                onUpdate()
            }
        }

        fun updateMaterial(material: Block) {
            if (material == block) return

            block = material
            onUpdate()
        }

        fun onTick() {
            ranTicks++
            if (ranTicks > taskState.stuckThreshold) {
                stuckTicks++
            }
        }

        fun onStuck(weight: Int = 1) {
            stuckTicks += weight
        }

        fun prepareSortInfo(event: SafeClientEvent, eyePos: Vec3d) {
            sides = when (taskState) {
                TaskState.PLACE, TaskState.LIQUID_FLOW, TaskState.LIQUID_SOURCE -> {
                    event.getNeighbourSequence(blockPos, placementSearch, maxReach, true).size
                }
                else -> 0
            }

            // ToDo: Function that makes a score out of those 3 parameters
            startDistance = startingBlockPos.distanceTo(blockPos)
            eyeDistance = eyePos.distanceTo(blockPos)
        }

        fun shuffle() {
            shuffle = nextInt(0, 1000)
        }

        fun prettyPrint(): String {
            return "    ${block.localizedName}@(${blockPos.asString()}) State: $taskState Timings: (Threshold: ${taskState.stuckThreshold} Timeout: ${taskState.stuckTimeout}) Priority: ${taskState.ordinal} Stuck: $stuckTicks"
        }

        private fun onUpdate() {
            stuckTicks = 0
            ranTicks = 0
        }

        override fun toString(): String {
            return "Block: ${block.localizedName} @ Position: (${blockPos.asString()}) State: ${taskState.name}"
        }

        override fun equals(other: Any?) = this === other
            || (other is BlockTask
            && blockPos == other.blockPos)

        override fun hashCode() = blockPos.hashCode()
    }

    enum class MovementState {
        RUNNING, PICKUP, BRIDGE
    }

    enum class TaskState(val stuckThreshold: Int, val stuckTimeout: Int, val color: ColorHolder) {
        DONE(69420, 0x22, ColorHolder(50, 50, 50)),
        BROKEN(1000, 1000, ColorHolder(111, 0, 0)),
        PLACED(1000, 1000, ColorHolder(53, 222, 66)),
        LIQUID_SOURCE(100, 100, ColorHolder(114, 27, 255)),
        LIQUID_FLOW(100, 100, ColorHolder(68, 27, 255)),
        PICKUP(500, 500, ColorHolder(252, 3, 207)),
        RESTOCK(500, 500, ColorHolder(252, 3, 207)),
        OPEN_CONTAINER(500, 500, ColorHolder(252, 3, 207)),
        BREAKING(100, 100, ColorHolder(240, 222, 60)),
        BREAK(20, 20, ColorHolder(222, 0, 0)),
        PLACE(20, 20, ColorHolder(35, 188, 254)),
        PENDING_BREAK(100, 100, ColorHolder(0, 0, 0)),
        PENDING_PLACE(100, 100, ColorHolder(0, 0, 0))
    }

}