package com.example.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

object AlarmSoundManager {
    private const val TAG = "AlarmSoundManager"
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Synchronized
    fun startAlarm(context: Context) {
        stopAlarm(context)
        Log.d(TAG, "Starting robust alarm sound and vibration loop")
        
        // 1. Play alarm sound in loop
        try {
            var alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed; trying Ringtone: ${e.message}")
            try {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = true
                }
                ringtone.play()
            } catch (ex: Exception) {
                Log.e(TAG, "Ringtone fallback failed: ${ex.message}")
            }
        }

        // 2. Vibrate in loop
        try {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 500, 250, 500, 250)
                val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE, 0)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 250, 500, 250), 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting vibrator: ${e.message}")
        }
    }

    @Synchronized
    fun stopAlarm(context: Context) {
        Log.d(TAG, "Stopping alarm sound and vibration")
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        } finally {
            mediaPlayer = null
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling vibrator: ${e.message}")
        } finally {
            vibrator = null
        }
    }
}
