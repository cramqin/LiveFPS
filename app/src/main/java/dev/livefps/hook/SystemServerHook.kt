package dev.livefps.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * System Server (package "android") hooks:
 * Treats LiveFPS's window as a system privileged window so tapjacking protection
 * (setHideOverlayWindows in Settings app, permission dialogs, package installer)
 * does NOT hide the overlay.
 */
object SystemServerHook {
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val windowStateCls = runCatching {
            XposedHelpers.findClass("com.android.server.wm.WindowState", lpparam.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedBridge.hookAllMethods(windowStateCls, "isNonSystemOverlayWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owningPackage = runCatching {
                        XposedHelpers.callMethod(param.thisObject, "getOwningPackage") as? String
                    }.getOrNull()
                    if (owningPackage == "dev.livefps") {
                        param.result = false
                    }
                }
            })
        }
    }
}
