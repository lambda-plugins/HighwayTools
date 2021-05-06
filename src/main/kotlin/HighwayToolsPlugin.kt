import com.lambda.client.plugin.api.Plugin

internal object HighwayToolsPlugin : Plugin() {

    override fun onLoad() {
        modules.add(HighwayTools)
        commands.add(HighwayToolsCommand)
        hudElements.add(HighwayToolsHud)
    }

    override fun onUnload() {
        modules.forEach {
            it.disable()
        }
    }
}