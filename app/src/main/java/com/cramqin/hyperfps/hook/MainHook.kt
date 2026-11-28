package com.cramqin.hyperfps.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class MainHook : XposedModule() {

    @Volatile private var statusBarHookInited = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        Log.i("HyperFPS", "MainHook onModuleLoaded: process=${param.processName}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        Log.i("HyperFPS", "MainHook onSystemServerStarting")
        runCatching {
            SystemServerHook.init(this, param.classLoader)
        }.onFailure {
            Log.e("HyperFPS", "Failed to init SystemServerHook", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        Log.i("HyperFPS", "MainHook onPackageReady: pkg=${param.packageName}, isFirst=${param.isFirstPackage}")
        when (param.packageName) {
            "com.android.systemui", "miui.systemui.plugin" -> {
                if (!statusBarHookInited) {
                    statusBarHookInited = true
                    runCatching {
                        StatusBarFpsHook.init(this, param.classLoader)
                    }.onFailure {
                        Log.e("HyperFPS", "Failed to init StatusBarFpsHook", it)
                    }
                }
            }
        }
    }
}
