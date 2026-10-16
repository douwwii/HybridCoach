package fr.hybridcoach

import android.app.*
import android.content.*
import android.os.*

class RestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.data?.lastPathSegment?.let {
            RestAlarm.fire(context, it, intent.getLongExtra("until", 0))
        }
    }
}

object RestAlarm {
    private fun pending(context: Context, id: String, until: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RestReceiver::class.java)
                .setData(android.net.Uri.parse("hybridcoach://rest/$id"))
                .putExtra("until", until),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, id: String, until: Long?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, id, until ?: 0)
        manager.cancel(pi)
        context
            .getSharedPreferences("rest", Context.MODE_PRIVATE)
            .edit()
            .putLong(id, until ?: 0)
            .apply()
        if (until != null && until > System.currentTimeMillis())
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi)
    }

    fun fire(context: Context, id: String, until: Long) {
        val preferences = context.getSharedPreferences("rest", Context.MODE_PRIVATE)
        if (until > 0 && preferences.getLong(id, 0) == until) {
            preferences.edit().putLong(id, 0).apply()
            vibrate(context)
        }
    }

    @Suppress("DEPRECATION")
    fun vibrate(context: Context) {
        val vibrator =
            if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
    }
}
