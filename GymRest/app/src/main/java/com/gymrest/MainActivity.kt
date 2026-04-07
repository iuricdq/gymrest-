package com.gymrest

import android.app.Activity
import android.content.*
import android.hardware.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.wear.ambient.AmbientModeSupport
import kotlin.math.*

class MainActivity : Activity(),
    SensorEventListener,
    AmbientModeSupport.AmbientCallbackProvider {

    // ── Sensors ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor:  Sensor? = null
    private var hrSensor:    Sensor? = null

    // ── Samsung Health ────────────────────────────────────────────────────────
        // ── State ─────────────────────────────────────────────────────────────────
    private var appState = AppState.IDLE
    private var restTotalSec = 60
    private var restRemainSec = 60
    private var setsCompleted = 0
    private var currentExercise = ""
    private var timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // ── Exercise detection buffers ────────────────────────────────────────────
    private val accelBuffer = FloatArray(20)
    private val gyroBuffer  = FloatArray(20)
    private var bufIdx = 0
    private var lastDetectionMs = 0L
    private var motionStopMs    = 0L
    private var inExercise      = false

    // ── Gesture ───────────────────────────────────────────────────────────────
    private val gestureDetector by lazy {
        GestureDetector(this, WristGestureListener())
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvTime:     TextView
    private lateinit var tvStatus:   TextView
    private lateinit var tvExercise: TextView
    private lateinit var tvSets:     TextView
    private lateinit var ringView:   RestRingView
    private lateinit var btnManual:  ImageButton

    // ── Ambient mode ─────────────────────────────────────────────────────────
    private lateinit var ambientController: AmbientModeSupport.AmbientController

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        ambientController = AmbientModeSupport.attach(this)

        tvTime     = findViewById(R.id.tv_time)
        tvStatus   = findViewById(R.id.tv_status)
        tvExercise = findViewById(R.id.tv_exercise)
        tvSets     = findViewById(R.id.tv_sets)
        ringView   = findViewById(R.id.ring_view)
        btnManual  = findViewById(R.id.btn_manual)

        initSensors()
        initSamsungHealth()
        setupManualButton()
        updateUI()
    }

    // ── Sensors ───────────────────────────────────────────────────────────────
    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hrSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        hrSensor?.let    { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> processAccel(event.values)
            Sensor.TYPE_GYROSCOPE           -> processGyro(event.values)
            Sensor.TYPE_HEART_RATE          -> processHR(event.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Accel / Motion detection ──────────────────────────────────────────────
    private fun processAccel(v: FloatArray) {
        val mag = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        accelBuffer[bufIdx % accelBuffer.size] = mag

        val avg = accelBuffer.average().toFloat()
        val now = SystemClock.elapsedRealtime()

        when (appState) {
            AppState.IDLE, AppState.DONE -> {
                if (avg > ACCEL_EXERCISE_THRESHOLD &&
                    now - lastDetectionMs > DETECTION_COOLDOWN_MS) {
                    lastDetectionMs = now
                    inExercise = true
                    motionStopMs = 0L
                    onExerciseDetected(classifyExercise(avg))
                }
            }
            AppState.ACTIVE -> {
                if (avg < ACCEL_STOP_THRESHOLD) {
                    if (motionStopMs == 0L) motionStopMs = now
                    if (now - motionStopMs > STOP_CONFIRM_MS) {
                        onSetCompleted()
                    }
                } else {
                    motionStopMs = 0L
                }
            }
            AppState.REST -> {
                // Early next-set warning if movement resumes
                if (avg > ACCEL_EXERCISE_THRESHOLD) {
                    ringView.setWarning(true)
                } else {
                    ringView.setWarning(false)
                }
            }
        }
        bufIdx++
    }

    private fun processGyro(v: FloatArray) {
        val mag = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        gyroBuffer[bufIdx % gyroBuffer.size] = mag

        // Wrist-twist gesture → pause/resume rest
        if (mag > GYRO_TWIST_THRESHOLD && appState == AppState.REST) {
            triggerGesturePause()
        }
    }

    private var lastHR = 0f
    private fun processHR(bpm: Float) {
        lastHR = bpm
        // High HR during REST may indicate user skipped rest
        if (appState == AppState.REST && bpm > HR_SKIP_THRESHOLD) {
            ringView.setWarning(true)
        }
    }

    // ── Exercise classification ───────────────────────────────────────────────
    private fun classifyExercise(accelMag: Float): String {
        val gyroMag = gyroBuffer.average().toFloat()
        return when {
            accelMag > 2.5f && gyroMag > 3.0f -> "Rosca Bíceps"
            accelMag > 2.0f && gyroMag < 1.5f -> "Agachamento"
            accelMag > 1.8f && gyroMag in 1.5f..3.0f -> "Supino"
            accelMag > 1.5f -> "Leg Press"
            else -> "Exercício"
        }
    }

    // ── State transitions ─────────────────────────────────────────────────────
    private fun onExerciseDetected(name: String) {
        currentExercise = name
        appState = AppState.ACTIVE
        runOnUiThread { updateUI() }
    }

    private fun onSetCompleted() {
        if (appState != AppState.ACTIVE) return
        setsCompleted++
        appState = AppState.REST
        startRestTimer()
        runOnUiThread { updateUI() }
        vibratePattern(VIBRATION_SET_DONE)
    }

    private fun onRestFinished() {
        appState = AppState.DONE
        runOnUiThread { updateUI() }
        vibratePattern(VIBRATION_REST_DONE)
    }

    // ── Rest timer ────────────────────────────────────────────────────────────
    private fun startRestTimer() {
        restRemainSec = restTotalSec
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = object : Runnable {
            override fun run() {
                if (restRemainSec <= 0) {
                    onRestFinished()
                    return
                }
                restRemainSec--
                ringView.setProgress(restRemainSec.toFloat() / restTotalSec)
                tvTime.text = formatTime(restRemainSec)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
    }

    // ── Gestures ──────────────────────────────────────────────────────────────
    private var gesturePaused = false

    private fun triggerGesturePause() {
        if (gesturePaused) {
            timerRunnable?.let { timerHandler.post(it) }
            gesturePaused = false
            tvStatus.text = "Descansando…"
        } else {
            timerRunnable?.let { timerHandler.removeCallbacks(it) }
            gesturePaused = true
            tvStatus.text = "Pausado"
        }
        vibratePattern(VIBRATION_GESTURE)
    }

    inner class WristGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent,
                             vX: Float, vY: Float): Boolean {
            // Upward fling on watch crown → skip rest
            if (vY < -FLING_VELOCITY_THRESHOLD && appState == AppState.REST) {
                stopTimer()
                appState = AppState.DONE
                runOnUiThread { updateUI() }
                vibratePattern(VIBRATION_GESTURE)
                return true
            }
            return false
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap → manual set done
            if (appState == AppState.ACTIVE) onSetCompleted()
            else if (appState == AppState.IDLE || appState == AppState.DONE) {
                appState = AppState.ACTIVE
                currentExercise = "Manual"
                runOnUiThread { updateUI() }
            }
            return true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    // ── Manual button ─────────────────────────────────────────────────────────
    private fun setupManualButton() {
        btnManual.setOnClickListener {
            when (appState) {
                AppState.IDLE, AppState.DONE -> {
                    appState = AppState.ACTIVE
                    currentExercise = "Manual"
                    updateUI()
                }
                AppState.ACTIVE -> onSetCompleted()
                AppState.REST   -> {
                    stopTimer()
                    appState = AppState.DONE
                    updateUI()
                    vibratePattern(VIBRATION_GESTURE)
                }
            }
        }
    }

    // ── Samsung Health (HR from health platform) ──────────────────────────────
    private fun initSamsungHealth() {
        // Samsung Health SDK optional — sensor-only mode active
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private fun updateUI() {
        when (appState) {
            AppState.IDLE -> {
                tvTime.text     = formatTime(restTotalSec)
                tvStatus.text   = "Aguardando…"
                tvExercise.text = "Mova-se para detectar"
                ringView.setProgress(0f)
                ringView.setColor(COLOR_IDLE)
                btnManual.setImageResource(R.drawable.ic_play)
            }
            AppState.ACTIVE -> {
                tvTime.text     = "-- : --"
                tvStatus.text   = "Exercitando"
                tvExercise.text = currentExercise
                ringView.setProgress(1f)
                ringView.setColor(COLOR_ACTIVE)
                btnManual.setImageResource(R.drawable.ic_check)
            }
            AppState.REST -> {
                tvTime.text     = formatTime(restRemainSec)
                tvStatus.text   = "Descansando"
                tvExercise.text = "$setsCompleted séries • $currentExercise"
                ringView.setColor(COLOR_REST)
                btnManual.setImageResource(R.drawable.ic_skip)
            }
            AppState.DONE -> {
                tvTime.text     = "Vai!"
                tvStatus.text   = "Pronto"
                tvExercise.text = "$setsCompleted séries • $currentExercise"
                ringView.setProgress(0f)
                ringView.setColor(COLOR_DONE)
                btnManual.setImageResource(R.drawable.ic_play)
            }
        }
        tvSets.text = setsCompleted.toString()
    }

    // ── Vibration ─────────────────────────────────────────────────────────────
    private fun vibratePattern(pattern: LongArray) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // ── Ambient mode ─────────────────────────────────────────────────────────
    override fun getAmbientCallback() = object : AmbientModeSupport.AmbientCallback() {
        override fun onEnterAmbient(ambientDetails: Bundle?) {
            tvTime.setTextColor(getColor(android.R.color.white))
            ringView.setAmbient(true)
        }
        override fun onExitAmbient() {
            ringView.setAmbient(false)
            updateUI()
        }
        override fun onUpdateAmbient() {
            tvTime.text = formatTime(restRemainSec)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun formatTime(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()

    }

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        const val ACCEL_EXERCISE_THRESHOLD = 1.4f   // g — início de série
        const val ACCEL_STOP_THRESHOLD     = 0.25f  // g — parou de mover
        const val GYRO_TWIST_THRESHOLD     = 4.5f   // rad/s — giro do pulso
        const val HR_SKIP_THRESHOLD        = 130f   // bpm — pulou descanso
        const val DETECTION_COOLDOWN_MS    = 3000L
        const val STOP_CONFIRM_MS          = 1800L
        const val FLING_VELOCITY_THRESHOLD = 800f

        val VIBRATION_SET_DONE  = longArrayOf(0, 80, 60, 80)
        val VIBRATION_REST_DONE = longArrayOf(0, 120, 80, 120, 80, 200)
        val VIBRATION_GESTURE   = longArrayOf(0, 50)

        val COLOR_IDLE   = 0xFF444444.toInt()
        val COLOR_ACTIVE = 0xFFE44444.toInt()
        val COLOR_REST   = 0xFF44AAFF.toInt()
        val COLOR_DONE   = 0xFF44FF88.toInt()
    }

    enum class AppState { IDLE, ACTIVE, REST, DONE }
}
