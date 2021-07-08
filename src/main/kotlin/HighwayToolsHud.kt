import com.lambda.client.event.SafeClientEvent
import com.lambda.client.plugin.api.PluginLabelHud
import trombone.Statistics.gatherStatistics
import trombone.Statistics.resetStats

internal object HighwayToolsHud : PluginLabelHud(
    name = "HighwayToolsHud",
    category = Category.MISC,
    description = "Hud for HighwayTools module",
    pluginMain = HighwayToolsPlugin
) {
    val simpleMovingAverageRange by setting("Moving Average", 60, 5..600, 5, description = "Sets the timeframe of the average in seconds")
    val showSession by setting("Show Session", true, description = "Toggles the Session section in HUD")
    val showLifeTime by setting("Show Lifetime", true, description = "Toggles the Lifetime section in HUD")
    val showPerformance by setting("Show Performance", true, description = "Toggles the Performance section in HUD")
    val showEnvironment by setting("Show Environment", true, description = "Toggles the Environment section in HUD")
    val showTask by setting("Show Task", true, description = "Toggles the Task section in HUD")
    val showEstimations by setting("Show Estimations", true, description = "Toggles the Estimations section in HUD")
    private val resetStats = setting("Reset Stats", false, description = "Resets the stats")

    init {
        resetStats.consumers.add { _, it ->
            if (it) resetStats()
            false
        }
    }

    override fun SafeClientEvent.updateText() {
        gatherStatistics(displayText)
    }
}