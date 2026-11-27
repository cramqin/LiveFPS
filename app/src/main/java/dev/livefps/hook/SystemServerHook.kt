package dev.livefps.hook

import android.util.Log
import io.github.libxposed.api.XposedModule

object SystemServerHook {
    fun init(module: XposedModule, classLoader: ClassLoader) {
        val windowStateCls = runCatching {
            Class.forName("com.android.server.wm.WindowState", false, classLoader)
        }.getOrNull() ?: return

        runCatching {
            val method = windowStateCls.getDeclaredMethod("isNonSystemOverlayWindow")
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val thisObj = chain.thisObject
                val owningPkg = runCatching {
                    val m = thisObj.javaClass.getMethod("getOwningPackage")
                    m.isAccessible = true
                    m.invoke(thisObj) as? String
                }.getOrNull()

                if (owningPkg == "dev.livefps") {
                    Log.d("LiveFPS", "Treating dev.livefps window as system overlay")
                    false
                } else {
                    chain.proceed()
                }
            }
            Log.i("LiveFPS", "SystemServerHook: successfully hooked isNonSystemOverlayWindow")
        }.onFailure {
            Log.e("LiveFPS", "SystemServerHook: failed to hook isNonSystemOverlayWindow", it)
        }
    }
}
