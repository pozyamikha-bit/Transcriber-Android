package com.transcriber.app.work

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Работа в фоне: исключение из оптимизации батареи (на Samsung — режим «Без ограничений»). */
object Battery {
    fun unrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Системный запрос «Разрешить работу в фоне без ограничений?». */
    @SuppressLint("BatteryLife")
    fun request(ctx: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            openAppSettings(ctx)
        }
    }

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
