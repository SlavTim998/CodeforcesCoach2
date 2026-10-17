package com.example.cfcoach

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ContestWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val prefs = applicationContext.getSharedPreferences("cfcoach", Context.MODE_PRIVATE)
        val active = CfApi.contests().filter { it.phase == "BEFORE" || it.phase == "CODING" }.map { it.id }.toSet()
        val key = "seenContestIds"
        val previous = prefs.getStringSet(key, null)
        if (previous != null) {
            val fresh = active - previous.mapNotNull(String::toIntOrNull).toSet()
            if (fresh.isNotEmpty()) notify("Новые соревнования Codeforces", "Появилось новых раундов: ${fresh.size}")
        }
        // First run establishes baseline to avoid alerting about the entire existing schedule.
        prefs.edit().putStringSet(key, active.map(Int::toString).toSet()).apply()
        val handle = prefs.getString("handle", "")?.trim().orEmpty()
        if (handle.isNotBlank()) {
            val rating = CfApi.profile(handle).rating
            val previousRating = prefs.getInt("lastRating", -1)
            if (previousRating >= 0 && previousRating != rating) notify("Рейтинг Codeforces изменился", "$handle: $previousRating → $rating")
            prefs.edit().putInt("lastRating", rating).apply()
        }
        Result.success()
    } catch (e: Exception) { Result.retry() }
    private fun notify(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "cfcoach_updates"
        manager.createNotificationChannel(NotificationChannel(channelId, "Codeforces updates", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(body)
            .setAutoCancel(true).build()
        manager.notify(title.hashCode(), notification)
    }
}
