package dev.livefps.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> SystemServerHook.init(lpparam)
            "com.android.systemui", "miui.systemui.plugin" -> StatusBarFpsHook.init(lpparam)
        }
    }
}
