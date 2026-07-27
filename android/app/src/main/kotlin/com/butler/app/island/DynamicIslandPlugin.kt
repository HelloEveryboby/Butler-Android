package com.butler.app.island

import android.content.Intent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission

@CapacitorPlugin(
    name = "DynamicIsland",
    permissions = [
        Permission(
            alias = "overlay",
            strings = ["android.permission.SYSTEM_ALERT_WINDOW"]
        )
    ]
)
class DynamicIslandPlugin : Plugin() {

    private var islandOverlay: DynamicIslandOverlay? = null

    @PluginMethod
    fun showCompact(call: PluginCall) {
        val icon = call.getString("icon", "info")
        val title = call.getString("title", "")
        val content = call.getString("content", "")

        ensureOverlay()
        islandOverlay?.showCompact(icon, title, content)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun expand(call: PluginCall) {
        islandOverlay?.expand()
        call.resolve()
    }

    @PluginMethod
    fun collapse(call: PluginCall) {
        islandOverlay?.collapse()
        call.resolve()
    }

    @PluginMethod
    fun update(call: PluginCall) {
        val title = call.getString("title", "")
        val content = call.getString("content", "")
        val icon = call.getString("icon")

        islandOverlay?.update(title, content, icon)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun hide(call: PluginCall) {
        islandOverlay?.hide {
            call.resolve(JSObject().put("ok", true))
        } ?: call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        val state = islandOverlay?.getState() ?: "HIDDEN"
        call.resolve(JSObject().put("state", state))
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val canDraw = android.provider.Settings.canDrawOverlays(context)
            call.resolve(JSObject().put("granted", canDraw))
        } else {
            call.resolve(JSObject().put("granted", true))
        }
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val canDraw = android.provider.Settings.canDrawOverlays(context)
            if (canDraw) {
                call.resolve(JSObject().put("granted", true))
            } else {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                call.resolve(JSObject().put("granted", false))
            }
        } else {
            call.resolve(JSObject().put("granted", true))
        }
    }

    override fun handleOnDestroy() {
        islandOverlay?.hide()
        super.handleOnDestroy()
    }

    private fun ensureOverlay() {
        if (islandOverlay == null) {
            islandOverlay = DynamicIslandOverlay(context)
        }
    }
}
