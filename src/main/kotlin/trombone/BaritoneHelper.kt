package trombone

import HighwayTools.goalRender
import com.lambda.client.util.BaritoneUtils

object BaritoneHelper {
    private var baritoneSettingAllowPlace = false
    private var baritoneSettingAllowBreak = false
    private var baritoneSettingRenderGoal = false

    fun setupBaritone() {
        baritoneSettingAllowPlace = BaritoneUtils.settings?.allowPlace?.value ?: true
        baritoneSettingAllowBreak = BaritoneUtils.settings?.allowBreak?.value ?: true
        BaritoneUtils.settings?.allowPlace?.value = false
//        BaritoneUtils.settings?.allowBreak?.value = false

        if (!goalRender) {
            baritoneSettingRenderGoal = BaritoneUtils.settings?.renderGoal?.value ?: true
            BaritoneUtils.settings?.renderGoal?.value = false
        }
    }

    fun resetBaritone() {
        BaritoneUtils.settings?.allowPlace?.value = baritoneSettingAllowPlace
//        BaritoneUtils.settings?.allowBreak?.value = baritoneSettingAllowBreak
        BaritoneUtils.settings?.renderGoal?.value = baritoneSettingRenderGoal
    }
}