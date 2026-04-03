package dev.surzhykyvych

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private lateinit var classifier: CleaningClassifier

    private lateinit var screenDashboard: View
    private lateinit var tvMainResult: TextView
    private lateinit var tvProbRest: TextView
    private lateinit var tvProbSweep: TextView
    private lateinit var tvProbWipe: TextView
    private lateinit var tvProbVacuum: TextView
    private lateinit var tvProbTrash: TextView
    private lateinit var progressRest: LinearProgressIndicator
    private lateinit var progressSweep: LinearProgressIndicator
    private lateinit var progressWipe: LinearProgressIndicator
    private lateinit var progressVacuum: LinearProgressIndicator
    private lateinit var progressTrash: LinearProgressIndicator

    private lateinit var screenTesting: View
    private lateinit var switchSimulate: MaterialSwitch
    private lateinit var dropdownActivity: AutoCompleteTextView

    private lateinit var progressAccX: LinearProgressIndicator
    private lateinit var progressAccY: LinearProgressIndicator
    private lateinit var progressAccZ: LinearProgressIndicator
    private lateinit var tvAccX: TextView
    private lateinit var tvAccY: TextView
    private lateinit var tvAccZ: TextView

    private lateinit var progressGyroX: LinearProgressIndicator
    private lateinit var progressGyroY: LinearProgressIndicator
    private lateinit var progressGyroZ: LinearProgressIndicator
    private lateinit var tvGyroX: TextView
    private lateinit var tvGyroY: TextView
    private lateinit var tvGyroZ: TextView

    private var latestAcc = floatArrayOf(0f, 0f, 0f)
    private var latestGyro = floatArrayOf(0f, 0f, 0f)

    private val windowData = ArrayDeque<FloatArray>(100)
    private val WINDOW_SIZE = 100

    private val ACC_MAX = 15f
    private val GYRO_MAX = 5f

    private var isSimulating = false
    private var simTypeIndex = 1
    private var simStepCounter = 0

    private var currentFreq = 2.0
    private var currentAmp = 3.0
    private var currentPhaseOffset = 0.0
    private var currentDriftX = 0f
    private var currentDriftY = 0f
    private var currentDriftZ = 0f
    private var currentTiltX = 0f
    private var currentTiltZ = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        setupNavigation()
        setupSimulator()

        classifier = CleaningClassifier(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        startDataCollection()
    }

    private fun initUI() {
        screenDashboard = findViewById(R.id.screenDashboard)
        screenTesting = findViewById(R.id.screenTesting)

        tvMainResult = findViewById(R.id.tvMainResult)
        tvProbRest = findViewById(R.id.tvProbRest)
        tvProbSweep = findViewById(R.id.tvProbSweep)
        tvProbWipe = findViewById(R.id.tvProbWipe)
        tvProbVacuum = findViewById(R.id.tvProbVacuum)
        tvProbTrash = findViewById(R.id.tvProbTrash)

        progressRest = findViewById(R.id.progressRest)
        progressSweep = findViewById(R.id.progressSweep)
        progressWipe = findViewById(R.id.progressWipe)
        progressVacuum = findViewById(R.id.progressVacuum)
        progressTrash = findViewById(R.id.progressTrash)

        switchSimulate = findViewById(R.id.switchSimulate)
        dropdownActivity = findViewById(R.id.dropdownActivity)

        progressAccX = findViewById(R.id.progressAccX)
        progressAccY = findViewById(R.id.progressAccY)
        progressAccZ = findViewById(R.id.progressAccZ)
        tvAccX = findViewById(R.id.tvAccX)
        tvAccY = findViewById(R.id.tvAccY)
        tvAccZ = findViewById(R.id.tvAccZ)

        progressGyroX = findViewById(R.id.progressGyroX)
        progressGyroY = findViewById(R.id.progressGyroY)
        progressGyroZ = findViewById(R.id.progressGyroZ)
        tvGyroX = findViewById(R.id.tvGyroX)
        tvGyroY = findViewById(R.id.tvGyroY)
        tvGyroZ = findViewById(R.id.tvGyroZ)
    }

    private fun setupNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            screenDashboard.visibility = View.GONE
            screenTesting.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_dashboard -> screenDashboard.visibility = View.VISIBLE
                R.id.nav_testing -> screenTesting.visibility = View.VISIBLE
            }
            true
        }
    }

    private fun setupSimulator() {
        val activities = resources.getStringArray(R.array.activity_classes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, activities)
        dropdownActivity.setAdapter(adapter)

        dropdownActivity.setOnItemClickListener { _, _, position, _ ->
            simTypeIndex = position
            simStepCounter = 0
        }

        switchSimulate.setOnCheckedChangeListener { _, isChecked ->
            isSimulating = isChecked
            dropdownActivity.isEnabled = isChecked
        }
    }

    override fun onResume() {
        super.onResume()
        accSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || isSimulating) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAcc[0] = event.values[0]
                latestAcc[1] = event.values[1]
                latestAcc[2] = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro[0] = event.values[0]
                latestGyro[1] = event.values[1]
                latestGyro[2] = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startDataCollection() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {

                if (isSimulating) {
                    if (simStepCounter % 100 == 0) {
                        currentPhaseOffset = Math.random() * Math.PI * 2

                        currentDriftX = ((Math.random() - 0.5) * 1.5).toFloat()
                        currentDriftY = ((Math.random() - 0.5) * 1.5).toFloat()
                        currentDriftZ = ((Math.random() - 0.5) * 1.5).toFloat()

                        val maxTilt = if (simTypeIndex == 0) 0.1 else 0.4
                        currentTiltX = ((Math.random() - 0.5) * maxTilt).toFloat()
                        currentTiltZ = ((Math.random() - 0.5) * maxTilt).toFloat()

                        when (simTypeIndex) {
                            1 -> { currentFreq = 2.0 + Math.random() * 1.0; currentAmp = 2.5 + Math.random() * 1.0 }
                            2 -> { currentFreq = 3.5 + Math.random() * 1.5; currentAmp = 1.5 + Math.random() * 1.0 }
                            3 -> { currentFreq = 1.5 + Math.random() * 1.0; currentAmp = 2.5 + Math.random() * 1.0 }
                            4 -> { currentFreq = 1.5 + Math.random() * 1.0; currentAmp = 4.0 + Math.random() * 2.0 }
                        }
                    }

                    val t = (simStepCounter / 100.0) * Math.PI * 2 + currentPhaseOffset

                    val norm = sqrt(currentTiltX * currentTiltX + currentTiltZ * currentTiltZ + 1f)
                    var simAccX = 9.8f * (currentTiltX / norm)
                    var simAccZ = 9.8f * (currentTiltZ / norm)
                    var simAccY = 9.8f * (1f / norm)

                    var simGyroX = 0f
                    var simGyroY = 0f
                    var simGyroZ = 0f

                    fun dirtySin(freq: Double, amp: Double): Float {
                        val base = sin(freq * t)
                        val harmonic = 0.3 * sin(freq * 2 * t)
                        return ((base + harmonic) * amp).toFloat()
                    }

                    fun dirtyCos(freq: Double, amp: Double): Float {
                        val base = cos(freq * t)
                        val harmonic = 0.3 * cos(freq * 2 * t)
                        return ((base + harmonic) * amp).toFloat()
                    }

                    when (simTypeIndex) {
                        0 -> {}
                        1 -> {
                            simAccX += dirtySin(currentFreq, currentAmp)
                            simGyroZ += dirtyCos(currentFreq, currentAmp)
                        }
                        2 -> {
                            simAccX += dirtySin(currentFreq, currentAmp)
                            simAccZ += dirtyCos(currentFreq, currentAmp)
                        }
                        3 -> {
                            simAccZ += dirtySin(currentFreq, currentAmp)
                            simGyroX += dirtySin(currentFreq, currentAmp)
                        }
                        4 -> {
                            simAccY += dirtySin(currentFreq, currentAmp)
                            simGyroY += dirtyCos(currentFreq, 2.5)
                        }
                    }

                    val mix = (Math.random() * 0.2).toFloat()
                    val tempAccX = simAccX
                    simAccX += simAccZ * mix
                    simAccZ += tempAccX * mix
                    simAccY += simAccX * 0.05f

                    val tempGyroX = simGyroX
                    simGyroX += simGyroZ * mix + (simAccZ * 0.1f)
                    simGyroZ += tempGyroX * mix + (simAccX * 0.1f)

                    if (Math.random() < 0.05) {
                        simAccX *= 0.2f
                        simAccZ *= 0.2f
                    }
                    if (Math.random() < 0.02) {
                        simAccX += (Math.random() * 10 - 5).toFloat()
                        simAccY += (Math.random() * 5).toFloat()
                    }

                    latestAcc[0] = simAccX + currentDriftX + (Math.random() * 2.4 - 1.2).toFloat()
                    latestAcc[1] = simAccY + currentDriftY + (Math.random() * 2.4 - 1.2).toFloat()
                    latestAcc[2] = simAccZ + currentDriftZ + (Math.random() * 2.4 - 1.2).toFloat()

                    latestGyro[0] = simGyroX + (Math.random() * 1.5 - 0.75).toFloat()
                    latestGyro[1] = simGyroY + (Math.random() * 1.5 - 0.75).toFloat()
                    latestGyro[2] = simGyroZ + (Math.random() * 1.5 - 0.75).toFloat()

                    simStepCounter++
                    updateLiveUI()
                }

                val combinedSample = floatArrayOf(
                    latestAcc[0], latestAcc[1], latestAcc[2],
                    latestGyro[0], latestGyro[1], latestGyro[2]
                )

                windowData.addLast(combinedSample)

                if (windowData.size == WINDOW_SIZE) {
                    val probabilities = classifier.classify(windowData.toList())

                    repeat(25) { windowData.removeFirst() }

                    updateDashboardUI(probabilities)
                }

                if (!isSimulating) {
                    updateLiveUI()
                }

                delay(20)
            }
        }
    }

    private fun toProgress(value: Float, maxVal: Float): Int {
        return (abs(value) / maxVal * 100).toInt().coerceIn(0, 100)
    }

    @SuppressLint("DefaultLocale")
    private suspend fun updateLiveUI() {
        withContext(Dispatchers.Main) {
            if (screenTesting.visibility != View.VISIBLE) return@withContext

            tvAccX.text = String.format("%.2f", latestAcc[0])
            tvAccY.text = String.format("%.2f", latestAcc[1])
            tvAccZ.text = String.format("%.2f", latestAcc[2])

            progressAccX.setProgressCompat(toProgress(latestAcc[0], ACC_MAX), true)
            progressAccY.setProgressCompat(toProgress(latestAcc[1], ACC_MAX), true)
            progressAccZ.setProgressCompat(toProgress(latestAcc[2], ACC_MAX), true)

            tvGyroX.text = String.format("%.2f", latestGyro[0])
            tvGyroY.text = String.format("%.2f", latestGyro[1])
            tvGyroZ.text = String.format("%.2f", latestGyro[2])

            progressGyroX.setProgressCompat(toProgress(latestGyro[0], GYRO_MAX), true)
            progressGyroY.setProgressCompat(toProgress(latestGyro[1], GYRO_MAX), true)
            progressGyroZ.setProgressCompat(toProgress(latestGyro[2], GYRO_MAX), true)
        }
    }

    private suspend fun updateDashboardUI(probabilities: FloatArray) {
        withContext(Dispatchers.Main) {
            var maxIndex = 0
            var maxProb = probabilities[0]
            for (i in 1 until probabilities.size) {
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxIndex = i
                }
            }

            if (maxProb > 0.5f) {
                tvMainResult.text = classifier.classNames[maxIndex]
            }

            val pRest = (probabilities[0] * 100).toInt()
            val pSweep = (probabilities[1] * 100).toInt()
            val pWipe = (probabilities[2] * 100).toInt()
            val pVacuum = (probabilities[3] * 100).toInt()
            val pTrash = (probabilities[4] * 100).toInt()

            tvProbRest.text = getString(R.string.percent_format, pRest)
            tvProbSweep.text = getString(R.string.percent_format, pSweep)
            tvProbWipe.text = getString(R.string.percent_format, pWipe)
            tvProbVacuum.text = getString(R.string.percent_format, pVacuum)
            tvProbTrash.text = getString(R.string.percent_format, pTrash)

            progressRest.setProgressCompat(pRest, true)
            progressSweep.setProgressCompat(pSweep, true)
            progressWipe.setProgressCompat(pWipe, true)
            progressVacuum.setProgressCompat(pVacuum, true)
            progressTrash.setProgressCompat(pTrash, true)
        }
    }
}