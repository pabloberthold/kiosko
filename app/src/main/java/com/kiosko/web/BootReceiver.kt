package com.kiosko.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reabre el kiosco después de reiniciar, solo si la opción está habilitada.
 *
 * Nota: Android 10+ limita el inicio de actividades en segundo plano. Si el
 * equipo no reabre la app, configurala como "app de inicio" (Home) o usá
 * aprovisionamiento de device owner (ver README).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = context.getSharedPreferences(
            MainActivity.PREFS,
            Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(MainActivity.KEY_AUTOSTART, false)) return

        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w("KioscoWeb", "No se pudo abrir el kiosco al arrancar", e)
        }
    }
}
