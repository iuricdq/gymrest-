package com.gymrest

import android.app.*
import android.content.Intent
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the accelerometer + gyro running
 * when the watch screen turns off during a rest period.
 *
 * The Activity binds to this service and receives callbacks via
 * the SensorCallback interface so the timer stays accurate.
 */
class SensorForegroundService : Service(), SensorEventListener {

    interface SensorCallback {
        fun onAccelUpdate(magnitude: Float)
        fun onGyroUpdate(magnitude: Float)
    }

    private val binder = LocalBinder()
    var callback: SensorCallback? = null

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor:  Sensor? = null

    inner class LocalBinder : Binder() {
        fun getService() = this@SensorForegroundService
    }

    override fun onBind(intent: Intent) = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Monitorando…"))
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    fun startMonitoring() {
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val mag = Math.sqrt((v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).toDouble()).toFloat()
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> callback?.onAccelUpdate(mag)
            Sensor.TYPE_GYROSCOPE           -> callback?.onGyroUpdate(mag)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "GymRest Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Monitoramento de exercício em segundo plano" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GymRest")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "gymrest_channel"
        const val NOTIF_ID   = 1001
    }
}
