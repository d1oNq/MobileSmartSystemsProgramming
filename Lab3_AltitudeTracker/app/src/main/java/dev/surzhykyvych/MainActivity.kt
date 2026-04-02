package dev.surzhykyvych

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

data class AltitudeRecord(
    val timestamp: Long = 0,
    val gpsAltitude: Double = 0.0,
    val baroAltitude: Double = 0.0,
    val finalAltitude: Double = 0.0
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvNetworkStatus: TextView
    private lateinit var tvCurrentAltitude: TextView
    private lateinit var tvCloudStats: TextView
    private lateinit var btnToggleTracking: Button
    private lateinit var tvSensorWarning: TextView

    private lateinit var sensorManager: SensorManager
    private var barometer: Sensor? = null

    private lateinit var database: DatabaseReference
    private var isFirebaseInitialized = false

    private var isTracking = false
    private var isSimulationMode = false
    private var sendIntervalMs = 30000L
    private val handler = Handler(Looper.getMainLooper())

    private var currentPressure = 1013.25f
    private var mockAltitude = 150.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        requestPermissions()
        initFirebase()
        initSensors()
        createNotificationChannel()

        btnToggleTracking.setOnClickListener {
            isTracking = !isTracking
            btnToggleTracking.text =
                if (isTracking) getString(R.string.btn_stop_tracking) else getString(R.string.btn_start_tracking)
            if (isTracking) startTracking() else stopTracking()
        }

        val switchSim =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSimulation)
        switchSim.setOnCheckedChangeListener { _, isChecked ->
            isSimulationMode = isChecked
            updateSensorWarningUI()

            if (isChecked) {
                Toast.makeText(this, "Симуляцію увімкнено!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSetInterval).setOnClickListener {
            val input =
                findViewById<TextInputEditText>(R.id.etInterval).text.toString().toLongOrNull()
            if (input != null && input > 0) {
                sendIntervalMs = input * 1000
                Toast.makeText(this, "Інтервал: $input сек", Toast.LENGTH_SHORT).show()
                findViewById<TextInputEditText>(R.id.etInterval).clearFocus()
            }
        }

        findViewById<Button>(R.id.btnExportCsv).setOnClickListener {
            exportDataToCsv()
        }

        findViewById<Button>(R.id.btnViewHistory).setOnClickListener {
            showHistoryDialog()
        }
    }

    private fun initUI() {
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        tvCurrentAltitude = findViewById(R.id.tvCurrentAltitude)
        tvCloudStats = findViewById(R.id.tvCloudStats)
        btnToggleTracking = findViewById(R.id.btnToggleTracking)
        tvSensorWarning = findViewById(R.id.tvSensorWarning)
    }

    private fun initFirebase() {
        try {
            database = FirebaseDatabase.getInstance().getReference("altitude_logs")
            database.keepSynced(true)
            isFirebaseInitialized = true

            val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
            connectedRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (connected) {
                        tvNetworkStatus.text = "Статус: Онлайн"
                        tvNetworkStatus.setTextColor("#388E3C".toColorInt())
                    } else {
                        tvNetworkStatus.text = "Статус: Офлайн (Кешування)"
                        tvNetworkStatus.setTextColor("#D32F2F".toColorInt())
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })

            fetchCloudStatistics()

        } catch (e: Exception) {
            Toast.makeText(this, "Помилка Firebase: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")

        if (!isEmulator) {
            barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (barometer != null) {
                sensorManager.registerListener(this, barometer, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } else {
            barometer = null
        }

        updateSensorWarningUI()
    }

    private fun updateSensorWarningUI() {
        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")

        when {
            isSimulationMode -> {
                tvSensorWarning.text = getString(R.string.warning_manual_simulation)
                tvSensorWarning.visibility = View.VISIBLE
            }

            isEmulator -> {
                tvSensorWarning.text = getString(R.string.warning_emulator)
                tvSensorWarning.visibility = View.VISIBLE
            }

            barometer == null -> {
                tvSensorWarning.text = getString(R.string.warning_no_barometer)
                tvSensorWarning.visibility = View.VISIBLE
            }

            else -> {
                tvSensorWarning.visibility = View.GONE
            }
        }
    }

    private fun startTracking() {
        val runnable = object : Runnable {
            @SuppressLint("SetTextI18n")
            override fun run() {
                if (!isTracking) return

                val calculatedAltitude = if (!isSimulationMode && barometer != null) {
                    44330.0 * (1.0 - (currentPressure / 1013.25).pow(1.0 / 5.255))
                } else {
                    mockAltitude += (Math.random() * 5.0) - 1.0
                    mockAltitude
                }

                runOnUiThread {
                    val formattedAlt = String.format(Locale.US, "%.1f", calculatedAltitude)
                    tvCurrentAltitude.text = "$formattedAlt м"
                }

                if (calculatedAltitude > 2000.0) {
                    sendCriticalNotification(calculatedAltitude)
                }

                sendDataToServer(calculatedAltitude)
                handler.postDelayed(this, sendIntervalMs)
            }
        }
        handler.post(runnable)
    }

    private fun stopTracking() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun sendDataToServer(altitude: Double) {
        if (!isFirebaseInitialized) return

        val timestamp = System.currentTimeMillis()
        val record = AltitudeRecord(timestamp, altitude, altitude, altitude)

        database.child(timestamp.toString()).setValue(record)
            .addOnFailureListener {
                Toast.makeText(this, "Помилка запису: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun fetchCloudStatistics() {
        if (!isFirebaseInitialized) return

        database.addValueEventListener(object : ValueEventListener {
            @SuppressLint("SetTextI18n")
            override fun onDataChange(snapshot: DataSnapshot) {
                var count = 0
                var maxAlt = 0.0

                for (child in snapshot.children) {
                    val record = child.getValue(AltitudeRecord::class.java)
                    if (record != null) {
                        count++
                        if (record.finalAltitude > maxAlt) maxAlt = record.finalAltitude
                    }
                }

                tvCloudStats.text =
                    "Хмарна статистика:\nВсього записів: $count\nМаксимальна висота: ${
                        String.format(
                            Locale.US,
                            "%.1f",
                            maxAlt
                        )
                    } м"
            }

            override fun onCancelled(error: DatabaseError) {
                tvCloudStats.text = "Помилка завантаження статистики"
            }
        })
    }

    private fun showHistoryDialog() {
        if (!isFirebaseInitialized) {
            Toast.makeText(this, "Firebase ще не підключено!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Завантаження історії...", Toast.LENGTH_SHORT).show()

        database.orderByKey().limitToLast(50)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val historyList = mutableListOf<String>()
                    val sdf = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

                    for (child in snapshot.children.reversed()) {
                        val record = child.getValue(AltitudeRecord::class.java)
                        if (record != null) {
                            val dateStr = sdf.format(Date(record.timestamp))
                            val altStr = String.format(Locale.US, "%.1f м", record.finalAltitude)
                            historyList.add("$dateStr – $altStr")
                        }
                    }

                    if (historyList.isEmpty()) {
                        historyList.add("Немає збережених записів")
                    }

                    val builder = android.app.AlertDialog.Builder(this@MainActivity)
                    builder.setTitle("Останні вимірювання (до 50)")

                    val adapter = android.widget.ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        historyList
                    )

                    builder.setAdapter(adapter, null)
                    builder.setPositiveButton("Закрити", null)
                    builder.show()
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@MainActivity,
                        "Помилка: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun exportDataToCsv() {
        if (!isFirebaseInitialized) return

        database.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = try {
                val file = File(getExternalFilesDir(null), "altitude_export.csv")
                val writer = FileWriter(file)
                writer.append("Date,Timestamp,Altitude(m)\n")

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                for (child in snapshot.children) {
                    val record = child.getValue(AltitudeRecord::class.java)
                    if (record != null) {
                        val dateStr = sdf.format(Date(record.timestamp))
                        writer.append("$dateStr,${record.timestamp},${record.finalAltitude}\n")
                    }
                }
                writer.flush()
                writer.close()
                Toast.makeText(
                    this@MainActivity,
                    "Експортовано в: ${file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()

            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Помилка експорту", Toast.LENGTH_SHORT).show()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ALTITUDE_ALERTS",
                "Сповіщення про висоту",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendCriticalNotification(alt: Double) {
        val builder = NotificationCompat.Builder(this, "ALTITUDE_ALERTS")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Критична висота!")
            .setContentText("Ви досягли висоти ${String.format(Locale.US, "%.0f", alt)} м.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, builder.build())
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        stopTracking()
    }
}