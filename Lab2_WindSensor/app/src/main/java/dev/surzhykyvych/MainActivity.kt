package dev.surzhykyvych

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvRawData: TextView
    private lateinit var tvAnalysis: TextView
    private lateinit var ivGraph: ImageView
    private lateinit var btnConnect: Button
    private lateinit var switchSimulation: SwitchMaterial

    private val history = mutableListOf<Float>()
    private var mockInterval = 3000L
    private var isSimulating = false
    private var isAutoMode = true
    private var currentWindSpeed = 5.0f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var btAdapter: BluetoothAdapter? = null
    private var btSocket: BluetoothSocket? = null
    private var btInputStream: InputStream? = null
    private var isBluetoothConnected = false
    private val ESP32_NAME = "ESP32_WindSensor"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvRawData = findViewById(R.id.tvRawData)
        tvAnalysis = findViewById(R.id.tvAnalysis)
        ivGraph = findViewById(R.id.ivGraph)
        btnConnect = findViewById(R.id.btnConnect)
        switchSimulation = findViewById(R.id.switchSimulation)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener {
            val useSimulation = switchSimulation.isChecked

            if (useSimulation) {
                if (!isSimulating) startSimulationMode() else disconnectDevice()
            } else {
                if (!isBluetoothConnected) connectRealBluetooth() else disconnectDevice()
            }
        }

        findViewById<Button>(R.id.btnSetInterval).setOnClickListener {
            val editText = findViewById<TextInputEditText>(R.id.etInterval)
            val newInt = editText.text.toString().toLongOrNull()

            if (newInt != null && newInt > 0) {
                mockInterval = newInt
                isAutoMode = false
                Toast.makeText(this, "Ручний режим: ${mockInterval}мс", Toast.LENGTH_SHORT).show()

                sendBluetoothCommand("INTERVAL:$newInt\n")

                editText.clearFocus()
            } else {
                isAutoMode = true
                mockInterval = 3000L
                editText.text?.clear()
                editText.clearFocus()
                sendBluetoothCommand("INTERVAL:AUTO\n")
                Toast.makeText(this, "Авторежим УВІМКНЕНО", Toast.LENGTH_SHORT).show()
            }
        }

        drawGraph()
    }

    private fun startSimulationMode() {
        isSimulating = true
        btnConnect.text = getString(R.string.btn_disconnect)
        switchSimulation.isEnabled = false
        Toast.makeText(this, "Симуляцію запущено", Toast.LENGTH_SHORT).show()

        val runnable = object : Runnable {
            @SuppressLint("SetTextI18n")
            override fun run() {
                if (!isSimulating) return

                currentWindSpeed += ((Math.random() - 0.5) * 3.0).toFloat()
                currentWindSpeed = currentWindSpeed.coerceIn(0f, 30f)

                val beaufort = getBeaufort(currentWindSpeed)
                val formattedSpeed = String.format(Locale.US, "%.2f", currentWindSpeed)
                val fakeJson = "{\"wind\":$formattedSpeed,\"beaufort\":$beaufort}"

                processReceivedJson(fakeJson)
                mainHandler.postDelayed(this, mockInterval)
            }
        }
        mainHandler.post(runnable)
    }

    @SuppressLint("MissingPermission")
    private fun connectRealBluetooth() {
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth не підтримується", Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkBluetoothPermissions()) return

        Toast.makeText(this, "Пошук $ESP32_NAME...", Toast.LENGTH_SHORT).show()

        val pairedDevices = btAdapter?.bondedDevices
        val espDevice = pairedDevices?.find { it.name == ESP32_NAME }

        if (espDevice == null) {
            Toast.makeText(this, "ESP32 не знайдено в спарених пристроях!", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                btSocket = espDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket?.connect()
                btInputStream = btSocket?.inputStream
                isBluetoothConnected = true

                withContext(Dispatchers.Main) {
                    btnConnect.text = getString(R.string.btn_disconnect)
                    switchSimulation.isEnabled = false
                    Toast.makeText(this@MainActivity, "Підключено до ESP32!", Toast.LENGTH_SHORT).show()
                }

                listenForBluetoothData()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Помилка підключення: ${e.message}", Toast.LENGTH_SHORT).show()
                    disconnectDevice()
                }
            }
        }
    }

    private fun listenForBluetoothData() {
        val buffer = ByteArray(1024)
        var bytes: Int

        while (isBluetoothConnected) {
            try {
                bytes = btInputStream?.read(buffer) ?: 0
                if (bytes > 0) {
                    val incomingMessage = String(buffer, 0, bytes).trim()
                    lifecycleScope.launch(Dispatchers.Main) {
                        processReceivedJson(incomingMessage)
                    }
                }
            } catch (e: Exception) {
                Log.e("Bluetooth", "З'єднання розірвано", e)
                isBluetoothConnected = false
                lifecycleScope.launch(Dispatchers.Main) { disconnectDevice() }
                break
            }
        }
    }

    private fun sendBluetoothCommand(command: String) {
        if (isBluetoothConnected && btSocket?.isConnected == true) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    btSocket?.outputStream?.write(command.toByteArray())
                } catch (e: Exception) {
                    Log.e("Bluetooth", "Помилка відправки", e)
                }
            }
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
                return false
            }
        }
        return true
    }

    @SuppressLint("SetTextI18n")
    private fun processReceivedJson(json: String) = try {
        val obj = JSONObject(json)
        val wind = obj.getDouble("wind").toFloat()
        val beaufort = obj.getInt("beaufort")
        val beaufortText = getBeaufortText(beaufort)

        tvRawData.text = "JSON: $json\nСтан: $beaufortText"
        updateAnalyticsAndGraph(wind)
    } catch (_: Exception) {
        tvRawData.text = "Помилка JSON: $json"
    }

    private fun updateAnalyticsAndGraph(newSpeed: Float) {
        history.add(newSpeed)
        if (history.size > 100) history.removeAt(0)

        autoAdjustInterval()

        val max = history.maxOrNull() ?: 0f
        val min = history.minOrNull() ?: 0f
        val avg = history.average()

        var trendText = "Стабільно"
        var rateOfChange = 0f

        if (history.size >= 2) {
            val last = history.last()
            val prev = history[history.size - 2]
            rateOfChange = (last - prev) / (mockInterval / 1000f)

            trendText = when {
                rateOfChange > 0.3 -> "Зростання"
                rateOfChange < -0.3 -> "Спадання"
                else -> "Стабільно"
            }
        }

        val powerModeStatus = if (!isAutoMode) {
            "Вимкнено (Ручний)"
        } else if (mockInterval >= 6000L) {
            "Увімкнено (Авто: 6с)"
        } else {
            "Очікування (Авто: 3с)"
        }

        tvAnalysis.text = String.format(
            Locale.US,
            "Мін: %.1f | Макс: %.1f | Сер: %.1f\nТренд: %s (%.2f м/с²)\nЕнергозбереження: %s",
            min, max, avg, trendText, rateOfChange, powerModeStatus
        )

        drawGraph()
    }

    private fun disconnectDevice() {
        isSimulating = false
        mainHandler.removeCallbacksAndMessages(null)

        isBluetoothConnected = false
        try {
            btInputStream?.close()
            btSocket?.close()
        } catch (e: Exception) { e.printStackTrace() }

        switchSimulation.isEnabled = true
        btnConnect.text = getString(R.string.btn_connect)
        Toast.makeText(this, "Відключено", Toast.LENGTH_SHORT).show()

        tvRawData.text = getString(R.string.text_waiting_data)
        tvAnalysis.text = getString(R.string.text_analysis_default)
        history.clear()
        drawGraph()
    }

    private fun autoAdjustInterval() {
        if (!isAutoMode || history.size < 2) return
        val diff = abs(history.last() - history[history.size - 2])
        mockInterval = if (diff < 0.5f) 6000L else 3000L
    }

    private fun drawGraph() {
        val width = 800
        val height = 400
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)

        if (history.isEmpty()) {
            ivGraph.setImageBitmap(bitmap)
            return
        }

        val paintLine = Paint().apply {
            color = "#1976D2".toColorInt()
            strokeWidth = 6f
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }

        val paintFill = Paint().apply {
            color = "#331976D2".toColorInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val path = Path()
        val fillPath = Path()

        val stepX = width.toFloat() / (history.size - 1).coerceAtLeast(1)
        val scaleY = height.toFloat() / 30f

        var currentX = 0f
        fillPath.moveTo(0f, height.toFloat())

        history.forEachIndexed { index, value ->
            val y = height - (value * scaleY)
            if (index == 0) {
                path.moveTo(currentX, y)
                fillPath.lineTo(currentX, y)
            } else {
                path.lineTo(currentX, y)
                fillPath.lineTo(currentX, y)
            }
            currentX += stepX
        }

        fillPath.lineTo(currentX - stepX, height.toFloat())
        fillPath.close()

        canvas.drawPath(fillPath, paintFill)
        canvas.drawPath(path, paintLine)

        ivGraph.setImageBitmap(bitmap)
    }

    private fun getBeaufort(speed: Float): Int {
        return when {
            speed < 0.3 -> 0
            speed < 1.6 -> 1
            speed < 3.4 -> 2
            speed < 5.5 -> 3
            speed < 8.0 -> 4
            speed < 10.8 -> 5
            speed < 13.9 -> 6
            speed < 17.2 -> 7
            speed < 20.8 -> 8
            speed < 24.5 -> 9
            speed < 28.5 -> 10
            speed <= 30.0 -> 11
            else -> 12
        }
    }

    private fun getBeaufortText(scale: Int): String {
        return when (scale) {
            0 -> "Штиль"
            1, 2, 3 -> "Слабкий вітер"
            4, 5 -> "Помірний вітер"
            6, 7 -> "Сильний вітер"
            8, 9 -> "Шторм"
            10, 11, 12 -> "Ураган"
            else -> "Невідомо"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectDevice()
    }
}