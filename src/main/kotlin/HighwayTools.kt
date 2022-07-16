import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerTravelEvent
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.setting.settings.impl.collection.CollectionSetting
import com.lambda.client.util.items.shulkerList
import com.lambda.client.util.threads.*
import net.minecraft.block.Block
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraftforge.fml.common.gameevent.TickEvent
import trombone.IO.DebugLevel
import trombone.IO.DisableMode
import trombone.IO.pauseCheck
import trombone.Pathfinder.updatePathing
import trombone.Renderer.renderOverlay
import trombone.Renderer.renderWorld
import trombone.Trombone.Structure
import trombone.Trombone.active
import trombone.Trombone.tick
import trombone.Trombone.onDisable
import trombone.Trombone.onEnable
import trombone.handler.Packet.handlePacket
import trombone.refactor.pathfinding.MovementStrategy
import trombone.refactor.pathfinding.Navigator
import trombone.refactor.task.TaskProcessor
import trombone.refactor.task.sequence.TaskSequenceStrategy

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
    private val page by setting("Page", Page.BLUEPRINT, description = "Switch between setting pages")

    private val defaultIgnoreBlocks = linkedSetOf(
        "minecraft:standing_sign",
        "minecraft:wall_sign",
        "minecraft:standing_banner",
        "minecraft:wall_banner",
        "minecraft:bedrock",
        "minecraft:end_portal",
        "minecraft:end_portal_frame",
        "minecraft:portal",
        "minecraft:piston_extension",
        "minecraft:barrier"
    )

    private val defaultEjectList = linkedSetOf(
        "minecraft:grass",
        "minecraft:dirt",
        "minecraft:netherrack",
        "minecraft:gravel",
        "minecraft:sand",
        "minecraft:stone",
        "minecraft:cobblestone"
    )

    // blueprint
    val mode by setting("Mode", Structure.HIGHWAY, { page == Page.BLUEPRINT }, description = "Choose the structure")
    val width by setting("Width", 6, 1..11, 1, { page == Page.BLUEPRINT }, description = "Sets the width of blueprint", unit = " blocks")
    val height by setting("Height", 4, 2..6, 1, { page == Page.BLUEPRINT && clearSpace }, description = "Sets height of blueprint", unit = " blocks")
    val backfill by setting("Backfill", false, { page == Page.BLUEPRINT && mode == Structure.TUNNEL }, description = "Fills the tunnel behind you")
    val clearSpace by setting("Clear Space", true, { page == Page.BLUEPRINT && mode == Structure.HIGHWAY }, description = "Clears out the tunnel if necessary")
    val cleanFloor by setting("Clean Floor", false, { page == Page.BLUEPRINT && mode == Structure.TUNNEL && !backfill }, description = "Cleans up the tunnels floor")
    val cleanRightWall by setting("Clean Right Wall", false, { page == Page.BLUEPRINT && mode == Structure.TUNNEL && !backfill }, description = "Cleans up the right wall")
    val cleanLeftWall by setting("Clean Left Wall", false, { page == Page.BLUEPRINT && mode == Structure.TUNNEL && !backfill }, description = "Cleans up the left wall")
    val cleanRoof by setting("Clean Roof", false, { page == Page.BLUEPRINT && mode == Structure.TUNNEL && !backfill }, description = "Cleans up the tunnels roof")
    val cleanCorner by setting("Clean Corner", false, { page == Page.BLUEPRINT && mode == Structure.TUNNEL && !cornerBlock && !backfill && width > 2 }, description = "Cleans up the tunnels corner")
    val cornerBlock by setting("Corner Block", false, { page == Page.BLUEPRINT && (mode == Structure.HIGHWAY || (mode == Structure.TUNNEL && !backfill && width > 2)) }, description = "If activated will break the corner in tunnel or place a corner while paving")
    val railing by setting("Railing", true, { page == Page.BLUEPRINT && mode == Structure.HIGHWAY }, description = "Adds a railing/rim/border to the highway")
    val railingHeight by setting("Railing Height", 1, 1..4, 1, { railing && page == Page.BLUEPRINT && mode == Structure.HIGHWAY }, description = "Sets height of railing", unit = " blocks")
    private val materialSaved = setting("Material", "minecraft:obsidian", { false })
    private val fillerMatSaved = setting("FillerMat", "minecraft:netherrack", { false })
    private val foodItem = setting("FoodItem", "minecraft:golden_apple", { false })
    val ignoreBlocks = setting(CollectionSetting("IgnoreList", defaultIgnoreBlocks, { false }))
    val ejectList = setting(CollectionSetting("Eject List", defaultEjectList))

    // behavior
    val maxReach by setting("Max Reach", 4.9f, 1.0f..7.0f, 0.1f, { page == Page.BEHAVIOR }, description = "Sets the range of the blueprint. Decrease when tasks fail!", unit = " blocks")
    val rubberbandTimeout by setting("Rubberband Timeout", 50, 5..100, 5, { page == Page.BEHAVIOR }, description = "Timeout for pausing after a lag")
    val taskTimeout by setting("Task Timeout", 8, 0..20, 1, { page == Page.BEHAVIOR }, description = "Timeout for waiting for the server to try again", unit = " ticks")
    val moveSpeed by setting("Packet Move Speed", 0.2f, 0.0f..1.0f, 0.01f, { page == Page.BEHAVIOR }, description = "Maximum player velocity per tick", unit = "m/t")
    val movementStrategy by setting("Movement Strategy", Navigator.EnumMoveStrategy.PROPAGATE, { page == Page.BEHAVIOR }, description = "Sets the movement strategy")
    val taskStrategy by setting("Task Selection Strategy", TaskProcessor.EnumTaskSequenceStrategy.ORIGIN, { page == Page.BEHAVIOR }, description = "Choose the strategy for task selection")

    // mining
    val breakDelay by setting("Break Delay", 1, 1..20, 1, { page == Page.MINING }, description = "Sets the delay ticks between break tasks", unit = " ticks")
    val miningSpeedFactor by setting("Mining Speed Factor", 1.0f, 0.0f..2.0f, 0.01f, { page == Page.MINING }, description = "Factor to manipulate calculated mining speed")
    val interactionLimit by setting("Interaction Limit", 20, 1..100, 1, { page == Page.MINING }, description = "Set the interaction limit per second", unit = " interactions/s")
    val multiBreak by setting("Multi Break", true, { page == Page.MINING }, description = "Breaks multiple instant breaking blocks intersecting with view vector")
    val packetFlood by setting("Packet Flood", false, { page == Page.MINING }, description = "Exploit for faster packet breaks. Sends START and STOP packet on same tick.")

    // placing
    val placeDelay by setting("Place Delay", 3, 1..20, 1, { page == Page.PLACING }, description = "Sets the delay ticks between placement tasks")
    val dynamicDelay by setting("Dynamic Place Delay", true, { page == Page.PLACING }, description = "Slows down on failed placement attempts")
    val illegalPlacements by setting("Illegal Placements", false, { page == Page.PLACING }, description = "Do not use on 2b2t. Tries to interact with invisible surfaces")
    val scaffold by setting("Scaffold", true, { page == Page.PLACING }, description = "Tries to bridge / scaffold when stuck placing")
    val placementSearch by setting("Place Deep Search", 2, 1..4, 1, { page == Page.PLACING }, description = "EXPERIMENTAL: Attempts to find a support block for placing against", unit = " blocks")

    // storage management
    val storageManagement by setting("Manage Storage", true, { page == Page.STORAGE_MANAGEMENT }, description = "Choose to interact with container using only packets")
    val searchEChest by setting("Search Ender Chest", false, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Allow access to your ender chest")
    val leaveEmptyShulkers by setting("Leave Empty Shulkers", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Does not break empty shulkers")
    val grindObsidian by setting("Grind Obsidian", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Destroy Ender Chests to obtain Obsidian")
    val pickupRadius by setting("Pickup radius", 8, 1..50, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Sets the radius for pickup", unit = " blocks")
    val fastFill by setting("Fast Fill", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Moves as many item stacks to inventory as possible")
    val keepFreeSlots by setting("Free Slots", 1, 0..30, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many inventory slots are untouched on refill", unit = " slots")
    val manageFood by setting("Manage Food", true, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Choose to manage food")
    val leastMaterial by setting("Least Material", 12, 0..64, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many material blocks are saved")
    val leastTools by setting("Least Tools", 1, 0..36, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many tools are saved")
    val leastEnder by setting("Least Ender Chests", 1, 0..64, 1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "How many ender chests are saved")
    val leastFood by setting("Least Food", 1, 0..64, 1, { page == Page.STORAGE_MANAGEMENT && manageFood && storageManagement}, description = "How many food items are saved")
    val minDistance by setting("Min Container Distance", 1.5, 0.0..3.0, 0.1, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Avoid player movement collision with placement.", unit = " blocks")
    val disableMode by setting("Disable Mode", DisableMode.NONE, { page == Page.STORAGE_MANAGEMENT }, description = "Choose action when bot is out of materials or tools")
    val usingProxy by setting("Proxy", false, { disableMode == DisableMode.LOGOUT && page == Page.STORAGE_MANAGEMENT }, description = "Enable this if you are using a proxy to call the given command")
    val proxyCommand by setting("Proxy Command", "/dc", { usingProxy && disableMode == DisableMode.LOGOUT && page == Page.STORAGE_MANAGEMENT }, description = "Command to be sent to log out")
    val preferEnderChests by setting("Prefer Ender Chests", false, { page == Page.STORAGE_MANAGEMENT && storageManagement }, description = "Prevent using raw material shulkers")

    // render
    val anonymizeStats by setting("Anonymize", false, { page == Page.RENDER }, description = "Censors all coordinates in HUD and Chat")
    val fakeSounds by setting("Fake Sounds", true, { page == Page.RENDER }, description = "Adds artificial sounds to the actions")
    val info by setting("Show Info", true, { page == Page.RENDER }, description = "Prints session stats in chat")
    val debugLevel by setting("Debug Level", DebugLevel.IMPORTANT, { page == Page.RENDER }, description = "Sets the debug log depth level")
    val goalRender by setting("Baritone Goal", false, { page == Page.RENDER }, description = "Renders the baritone goal")
    val showCurrentPos by setting("Current Pos", false, { page == Page.RENDER }, description = "Renders the current position")
    val filled by setting("Filled", true, { page == Page.RENDER }, description = "Renders colored task surfaces")
    val outline by setting("Outline", true, { page == Page.RENDER }, description = "Renders colored task outlines")
    val popUp by setting("Pop up", true, { page == Page.RENDER }, description = "Funny render effect")
    val popUpSpeed by setting("Pop up speed", 150, 0..500, 1, { popUp && page == Page.RENDER }, description = "Sets speed of the pop up effect", unit = "ms")
    val showDebugRender by setting("Debug Render", false, { page == Page.RENDER }, description = "Render debug info on tasks")
    val textScale by setting("Text Scale", 1.0f, 0.0f..4.0f, 0.25f, { showDebugRender && page == Page.RENDER }, description = "Scale of debug text")
    val disableWarnings by setting("Disable Warnings", false, { page == Page.RENDER }, description = "DANGEROUS: Disable warnings on enable")
    val aFilled by setting("Filled Alpha", 26, 0..255, 1, { filled && page == Page.RENDER }, description = "Sets the opacity")
    val aOutline by setting("Outline Alpha", 91, 0..255, 1, { outline && page == Page.RENDER }, description = "Sets the opacity")
    val thickness by setting("Thickness", 2.0f, 0.25f..4.0f, 0.25f, { outline && page == Page.RENDER }, description = "Sets thickness of outline")

    private enum class Page {
        BLUEPRINT, BEHAVIOR, MINING, PLACING, STORAGE_MANAGEMENT, RENDER
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