import HighwayTools.gatherStatistics
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.plugin.api.PluginLabelHud

internal object HighwayToolsHud : PluginLabelHud(
    name = "HighwayToolsHud",
    category = Category.MISC,
    description = "Hud for HighwayTools module",
    pluginMain = HighwayToolsPlugin
) {
    override fun SafeClientEvent.updateText() {
        gatherStatistics(displayText)
    }
}