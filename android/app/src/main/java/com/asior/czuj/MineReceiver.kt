package com.asior.czuj

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Przycisk „To moje” w powiadomieniu o alarmie. Nie uruchamia strażnika, tylko zapisuje urządzenie.
class MineReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val addr = intent.getStringExtra("addr") ?: return
        GuardService.markMine(ctx, addr, intent.getStringExtra("tag") ?: "tracker")
        ctx.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("nid", 0))
    }
}
