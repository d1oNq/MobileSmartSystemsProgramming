package dev.surzhykyvych

import android.annotation.SuppressLint
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvValues: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvValues = findViewById(R.id.tvValues)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            tvStatus.text = "Акселерометр не знайдено на цьому пристрої"
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        tvValues.text = "X: %.1f  Y: %.1f  Z: %.1f".format(x, y, z)

        when {
            y < -7.0 -> {
                tvStatus.text = "Телефон перевернуто догори ногами!"
                tvStatus.setTextColor(Color.RED)
            }

            y > 7.0 -> {
                tvStatus.text = "Нормальне положення"
                tvStatus.setTextColor("#2E7D32".toColorInt())
            }

            else -> {
                tvStatus.text = "Горизонтальне положення"
                tvStatus.setTextColor("#1565C0".toColorInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}