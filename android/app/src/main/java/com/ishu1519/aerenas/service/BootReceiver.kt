package com.ishu1519.aerenas.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ishu1519.aerenas.utils.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.autoStartOnBoot) {
            val serviceIntent = Intent(context, WebDavService::class.java).apply {
                action = WebDavService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
