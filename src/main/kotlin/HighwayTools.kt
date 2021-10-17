import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerTravelEvent
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.setting.settings.impl.collection.CollectionSetting
import com.lambda.client.util.items.shulkerList
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.runSafeR
import com.lambda.client.util.threads.safeListener
import com.lambda.event.listener.listener
import net.minecraft.block.Block
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraftforge.fml.common.gameevent.TickEvent
import trombone.IO.DebugMessages
import trombone.IO.DisableMode
import trombone.IO.pauseCheck
import trombone.Pathfinder.updatePathing
import trombone.Renderer.renderOverlay
import trombone.Renderer.renderWorld
import trombone.Trombone.Mode
import trombone.Trombone.active
import trombone.Trombone.tick
import trombone.Trombone.onDisable
import trombone.Trombone.onEnable
import trombone.handler.Packet.handlePacket
import trombone.handler.Player.LimitMode
import trombone.handler.Player.RotationMode

/**
 * @author Avanatiker
 * @since 20/08/2020
 */
object HighwayTools : PluginModule(
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
    val mode by setting("Mode", Mode.HIGHWAY, { page == Page.BUILD }, description = "Choose the structure")
    val width by setting("Width", 6, 1..11, 1, { page == Page.BUILD }, description = "Sets the width of blueprint")
    val height by setting("Height", 4, 1..6, 1, { page == Page.BUILD && clearSpace }, description = "Sets height of blueprint")
    val backfill by setting("Backfill", false, { page == Page.BUILD && mode == Mode.TUNNEL }, description = "Fills the tunnel behind you")
    val clearSpace by setting("Clear Space", true, { page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Clears out the tunnel if necessary")
    val cleanFloor by setting("Clean Floor", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the tunnels floor")
    val cleanRightWall by setting("Clean Right Wall", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the right wall")
    val cleanLeftWall by setting("Clean Left Wall", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the left wall")
    val cleanRoof by setting("Clean Roof", false, { page == Page.BUILD && mode == Mode.TUNNEL && !backfill }, description = "Cleans up the tunnels roof")
    val cleanCorner by setting("Clean Corner", false, { page == Page.BUILD && mode == Mode.TUNNEL && !cornerBlock && !backfill && width > 2 }, description = "Cleans up the tunnels corner")
    val cornerBlock by setting("Corner Block", false, { page == Page.BUILD && (mode == Mode.HIGHWAY || (mode == Mode.TUNNEL && !backfill && width > 2)) }, description = "If activated will break the corner in tunnel or place a corner while paving")
    val railing by setting("Railing", true, { page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Adds a railing / rim / border to the highway")
    val railingHeight by setting("Railing Height", 1, 1..4, 1, { railing && page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Sets height of railing")
    private val materialSaved = setting("Material", "minecraft:obsidian", { false })
    private val fillerMatSaved = setting("FillerMat", "minecraft:netherrack", { false })
    private val foodItem = setting("FoodItem", "minecraft:golden_apple", { false })
    val ignoreBlocks = setting(CollectionSetting("IgnoreList", defaultIgnoreBlocks, { false }))

    // behavior settings
    val interacting by setting("Rotation Mode", RotationMode.SPOOF, { page == Page.BEHAVIOR }, description = "Force view client side, only server side or no interaction at all")
    val dynamicDelay by setting("Dynamic Place Delay", true, { page == Page.BEHAVIOR }, description = "Slows down on failed placement attempts")
    val placeDelay by setting("Place Delay", 3, 1..20, 1, { page == Page.BEHAVIOR }, description = "Sets the delay ticks between placement tasks")
    val breakDelay by setting("Break Delay", 1, 1..20, 1, { page == Page.BEHAVIOR }, description = "Sets the delay ticks between break tasks")
    val illegalPlacements by setting("Illegal Placements", false, { page == Page.BEHAVIOR }, description = "Do not use on 2b2t. Tries to interact with invisible surfaces")
    val bridging by setting("Bridging", true, { page == Page.BEHAVIOR }, description = "Tries to bridge / scaffold when stuck placing")
    val instantMine by setting("Instant Mine", true, { page == Page.BEHAVIOR }, description = "Instant mine NCP exploit.")
    val alwaysBoth by setting("More Packets", false, { page == Page.BEHAVIOR }, description = "Exploit for faster breaks.")
    val multiBuilding by setting("Shuffle Tasks", false, { page == Page.BEHAVIOR }, description = "Only activate when working with several players")
    val taskTimeout by setting("Task Timeout", 8, 0..20, 1, { page == Page.BEHAVIOR }, description = "Timeout for waiting for the server to try again")
    val rubberbandTimeout by setting("Rubberband Timeout", 50, 5..100, 5, { page == Page.BEHAVIOR }, description = "Timeout for pausing after a lag")
    val maxReach by setting("Max Reach", 4.9f, 1.0f..7.0f, 0.1f, { page == Page.BEHAVIOR }, description = "Sets the range of the blueprint. Decrease when tasks fail!")
    val maxBreaks by setting("Multi Break", 1, 1..5, 1, { page == Page.BEHAVIOR }, description = "EXPERIMENTAL: Breaks multiple instant breaking blocks per tick in view")
    val limitOrigin by setting("Limited by", LimitMode.FIXED, { page == Page.BEHAVIOR }, description = "Changes the origin of limit: Client / Server TPS")
    val limitFactor by setting("Limit Factor", 1.0f, 0.5f..2.0f, 0.01f, { page == Page.BEHAVIOR }, description = "EXPERIMENTAL: Factor for TPS which acts as limit for maximum breaks per second.")
    val placementSearch by setting("Place Deep Search", 2, 1..4, 1, { page == Page.BEHAVIOR }, description = "EXPERIMENTAL: Attempts to find a support block for placing against")
    val moveSpeed by setting("Packet Move Speed", 0.2f, 0.0f..1.0f, 0.01f, { page == Page.BEHAVIOR }, description = "Maximum player velocity per tick")

    // storage management
    val storageManagement by setting("Manage Storage", true, { page == Page.STORAGE_MANAGEMENT }, description = "Choose to interact with container using only packets.")
    val searchEChest by setting("Search Ender Chest", false, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Allow access to your ender chest.")
    val leaveEmptyShulkers by setting("Leave Empty Shulkers", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Does not break empty shulkers.")
    val grindObsidian by setting("Grind Obsidian", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Destroy Ender Chests to obtain Obsidian.")
    val manageFood by setting("Manage Food", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Choose to manage food.")
    val saveMaterial by setting("Save Material", 12, 0..64, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many material blocks are saved")
    val saveTools by setting("Save Tools", 1, 0..36, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many tools are saved")
    val saveEnder by setting("Save Ender Chests", 1, 0..64, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many ender chests are saved")
    val saveFood by setting("Save Food", 1, 0..64, 1, { page == Page.STORAGE_MANAGEMENT && manageFood && storageManagement}, description = "How many food items are saved")

    val disableMode by setting("Disable Mode", DisableMode.NONE, { page == Page.STORAGE_MANAGEMENT }, description = "Choose action when bot is out of materials or tools")
    val usingProxy by setting("Proxy", false, { disableMode == DisableMode.LOGOUT && page == Page.STORAGE_MANAGEMENT }, description = "Enable this if you are using a proxy to call the given command")
    val proxyCommand by setting("Proxy Command", "/dc", { usingProxy && disableMode == DisableMode.LOGOUT && page == Page.STORAGE_MANAGEMENT }, description = "Command to be sent to log out")

    // config
    val anonymizeStats by setting("Anonymize", false, { page == Page.CONFIG }, description = "Censors all coordinates in HUD and Chat")
    val fakeSounds by setting("Fake Sounds", true, { page == Page.CONFIG }, description = "Adds artificial sounds to the actions")
    val info by setting("Show Info", true, { page == Page.CONFIG }, description = "Prints session stats in chat")
    val printDebug by setting("Show Queue", false, { page == Page.CONFIG }, description = "Shows task queue in HUD")
    val debugMessages by setting("Debug Messages", DebugMessages.IMPORTANT, { page == Page.CONFIG }, description = "Sets the debug log depth level")
    val goalRender by setting("Goal Render", false, { page == Page.CONFIG }, description = "Renders the baritone goal")
    val showCurrentPos by setting("Current Pos Render", false, { page == Page.CONFIG }, description = "Renders the current position")
    val filled by setting("Filled", true, { page == Page.CONFIG }, description = "Renders colored task surfaces")
    val outline by setting("Outline", true, { page == Page.CONFIG }, description = "Renders colored task outlines")
    val popUp by setting("Pop up", true, { page == Page.CONFIG }, description = "Funny render effect")
    val popUpSpeed by setting("Pop up speed", 150, 0..500, 1, { popUp && page == Page.CONFIG }, description = "Sets speed of the pop up effect")
    val showDebugRender by setting("Debug Render", false, { page == Page.CONFIG }, description = "Render debug info on tasks")
    val disableWarnings by setting("Disable Warnings", false, { page == Page.CONFIG }, description = "DANGEROUS: Disable warnings on enable")
    val textScale by setting("Text Scale", 1.0f, 0.0f..4.0f, 0.25f, { showDebugRender && page == Page.CONFIG }, description = "Scale of debug text")
    val aFilled by setting("Filled Alpha", 26, 0..255, 1, { filled && page == Page.CONFIG }, description = "Sets the opacity")
    val aOutline by setting("Outline Alpha", 91, 0..255, 1, { outline && page == Page.CONFIG }, description = "Sets the opacity")
    val thickness by setting("Thickness", 2.0f, 0.25f..4.0f, 0.25f, { outline && page == Page.CONFIG }, description = "Sets thickness of outline")

    private enum class Page {
        BUILD, BEHAVIOR, STORAGE_MANAGEMENT, CONFIG
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
    var food: Item
        get() = Item.getByNameOrId(foodItem.value) ?: Items.GOLDEN_APPLE
        set(value) {
            foodItem.value = value.registryName.toString()
        }


    override fun isActive(): Boolean {
        return isEnabled && active
    }

    init {
        shulkerList.forEach {
            ignoreBlocks.add(it.registryName.toString())
        }

        onEnable {
            runSafeR {
                onEnable()
            } ?: disable()
        }

        onDisable {
            runSafe {
                onDisable()
            }
        }
    }

    init {
        safeListener<PacketEvent.Receive> {
            handlePacket(it.packet)
        }

        listener<RenderWorldEvent> {
            renderWorld()
        }

        listener<RenderOverlayEvent> {
            renderOverlay()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase == TickEvent.Phase.START) tick()
        }

        safeListener<PlayerTravelEvent> {
            if (!pauseCheck()) updatePathing()
        }
    }
}