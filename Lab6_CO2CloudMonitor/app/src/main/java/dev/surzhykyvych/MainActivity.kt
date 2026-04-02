package dev.surzhykyvych

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var tvCo2Value: TextView
    private lateinit var tvAirQuality: TextView
    private lateinit var tvStats: TextView
    private lateinit var lineChart: LineChart
    private lateinit var btnStartStop: Button
    private lateinit var chipGroupDevices: ChipGroup

    private var currentRecordsList: List<Co2Record> = emptyList()
    private val selectedDevices = mutableSetOf<String>()
    private val deviceColors = mutableMapOf<String, Int>()
    private val colorPalette = listOf(
        "#1976D2".toColorInt(),
        "#D32F2F".toColorInt(),
        "#388E3C".toColorInt(),
        "#F57C00".toColorInt(),
        "#8E24AA".toColorInt()
    )

    private var wasCritical = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        setupNavigation()
        setupChartAppearance()
        observeViewModel()
        setupListeners()
    }

    private fun initUI() {
        tvCo2Value = findViewById(R.id.tvCo2Value)
        tvAirQuality = findViewById(R.id.tvAirQuality)
        tvStats = findViewById(R.id.tvStats)
        lineChart = findViewById(R.id.lineChart)
        btnStartStop = findViewById(R.id.btnStartStop)
        chipGroupDevices = findViewById(R.id.chipGroupDevices)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isRecording.collect { isRec ->
                        btnStartStop.text =
                            if (isRec) getString(R.string.btn_stop_record) else getString(R.string.btn_start_record)
                    }
                }
                launch {
                    viewModel.currentCo2.collect { value ->
                        updateDisplay(value)
                        if (value > 1500 && !wasCritical) {
                            Toast.makeText(
                                this@MainActivity,
                                "Критичний рівень CO2!",
                                Toast.LENGTH_SHORT
                            ).show()
                            wasCritical = true
                        } else if (value <= 1500) {
                            wasCritical = false
                        }
                    }
                }
                launch {
                    viewModel.recordsFlow.collect { records ->
                        currentRecordsList = records
                        updateDeviceChips(records)
                        updateChart()
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        btnStartStop.setOnClickListener { viewModel.toggleRecording() }

        val btnClearDb = findViewById<Button>(R.id.btnClearDb)

        btnClearDb.setOnClickListener {
            viewModel.clearAllData()
            Toast.makeText(this, "Базу очищено", Toast.LENGTH_SHORT).show()
            updateStatsUI()
        }

        btnClearDb.setOnLongClickListener {
            viewModel.generateFakeHistoricalData()
            Toast.makeText(this, "Згенеровано дані за 7 днів!", Toast.LENGTH_LONG).show()
            true
        }

        findViewById<Button>(R.id.btnSetInterval).setOnClickListener {
            val input = findViewById<EditText>(R.id.etInterval).text.toString()
            val seconds = input.toLongOrNull()
            if (seconds != null) {
                viewModel.setInterval(seconds)
                Toast.makeText(this, "Інтервал: $seconds сек", Toast.LENGTH_SHORT).show()
                findViewById<EditText>(R.id.etInterval).clearFocus()
            }
        }

        findViewById<Button>(R.id.btnExportCsv).setOnClickListener { exportToCsv() }
        findViewById<Button>(R.id.btnViewTextHistory).setOnClickListener {
            showTextHistoryDialog(
                currentRecordsList
            )
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        val toggleGroup =
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleGroupFilters)
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val now = System.currentTimeMillis()
                when (checkedId) {
                    R.id.btnFilterHour -> viewModel.currentFilterTime.value =
                        now - MainViewModel.HOUR

                    R.id.btnFilterDay -> viewModel.currentFilterTime.value = now - MainViewModel.DAY
                    R.id.btnFilterWeek -> viewModel.currentFilterTime.value =
                        now - MainViewModel.WEEK
                }
                lineChart.fitScreen()
            }
        }
    }

    private fun setupNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        val home = findViewById<View>(R.id.screenHome)
        val chart = findViewById<View>(R.id.screenChart)
        val stats = findViewById<View>(R.id.screenStats)

        nav.setOnItemSelectedListener { item ->
            home.visibility = View.GONE
            chart.visibility = View.GONE
            stats.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_home -> home.visibility = View.VISIBLE
                R.id.nav_chart -> chart.visibility = View.VISIBLE
                R.id.nav_stats -> {
                    stats.visibility = View.VISIBLE
                    updateStatsUI()
                }
            }
            true
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDisplay(value: Float) {
        tvCo2Value.text = "%.0f ppm".format(value)
        when {
            value < 800 -> {
                tvAirQuality.text = getString(R.string.default_air_quality)
                tvAirQuality.setTextColor("#4CAF50".toColorInt())
                tvCo2Value.setTextColor("#4CAF50".toColorInt())
            }

            value < 1200 -> {
                tvAirQuality.text = "Помірна якість"
                tvAirQuality.setTextColor("#FF9800".toColorInt())
                tvCo2Value.setTextColor("#FF9800".toColorInt())
            }

            else -> {
                tvAirQuality.text = "Погана якість! Провітріть!"
                tvAirQuality.setTextColor("#D32F2F".toColorInt())
                tvCo2Value.setTextColor("#D32F2F".toColorInt())
            }
        }
    }

    private fun setupChartAppearance() {
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceChips(records: List<Co2Record>) {
        val uniqueDevices = records.map { it.deviceId }.distinct()

        if (uniqueDevices.size != deviceColors.size) {
            chipGroupDevices.removeAllViews()

            uniqueDevices.forEach { deviceId ->
                if (!deviceColors.containsKey(deviceId)) {
                    deviceColors[deviceId] = colorPalette[deviceColors.size % colorPalette.size]
                    selectedDevices.add(deviceId)
                }

                val chip = Chip(this).apply {
                    val shortName = if (deviceId.length > 5) deviceId.take(5) else deviceId
                    text = "Device $shortName"
                    isCheckable = true
                    isChecked = selectedDevices.contains(deviceId)
                    setChipBackgroundColorResource(android.R.color.transparent)
                    setChipStrokeColorResource(android.R.color.darker_gray)
                    chipStrokeWidth = 2f

                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedDevices.add(deviceId) else selectedDevices.remove(
                            deviceId
                        )
                        updateChart()
                    }
                }
                chipGroupDevices.addView(chip)
            }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private fun updateChart() {
        if (currentRecordsList.isEmpty()) {
            lineChart.clear()
            return
        }

        val referenceTime = currentRecordsList.first().timestamp
        val dataSets = mutableListOf<LineDataSet>()

        val groupedRecords = currentRecordsList.groupBy { it.deviceId }

        groupedRecords.forEach { (deviceId, records) ->
            if (selectedDevices.contains(deviceId)) {
                val entries = records.map { record ->
                    Entry((record.timestamp - referenceTime).toFloat(), record.co2Level)
                }

                val dataSet = LineDataSet(entries, "Пристрій ${deviceId.take(4)}").apply {
                    color = deviceColors[deviceId] ?: Color.BLACK
                    setDrawCircles(false)
                    lineWidth = 2.5f
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                dataSets.add(dataSet)
            }
        }

        lineChart.xAxis.valueFormatter = object : ValueFormatter() {
            private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                val originalTime = value.toLong() + referenceTime
                return sdf.format(Date(originalTime))
            }
        }

        lineChart.data = LineData(dataSets.toList())
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
    }

    private fun updateStatsUI() {
        lifecycleScope.launch {
            val stats = viewModel.getStats()
            withContext(Dispatchers.Main) {
                tvStats.text = buildString {
                    append("Статистика бази даних:\n\n")
                    append("Кількість записів: ${stats.count}\n")
                    append("Мінімальний CO₂: %.0f ppm\n".format(stats.min))
                    append("Максимальний CO₂: %.0f ppm\n".format(stats.max))
                    append("Середнє значення: %.0f ppm".format(stats.avg))
                }
            }
        }
    }

    private fun showTextHistoryDialog(records: List<Co2Record>) {
        if (records.isEmpty()) {
            Toast.makeText(this, "Немає даних", Toast.LENGTH_SHORT).show()
            return
        }
        val sdf = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
        val historyList = records.reversed().map { record ->
            val dup = if (record.isDuplicate) "[DUP] " else ""
            "${dup}${sdf.format(Date(record.timestamp))} – %.0f ppm".format(record.co2Level)
        }
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Історія (${records.size})")
        builder.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                historyList
            ), null
        )
        builder.setPositiveButton("Закрити", null)
        builder.show()
    }

    private fun exportToCsv() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timeFilter = viewModel.currentFilterTime.value
                val records = viewModel.getRecordsForExport(timeFilter)

                val file = File(getExternalFilesDir(null), "co2_data_export.csv")
                file.writeText("Timestamp,Date,CO2_ppm,DeviceId,DeviceModel,OS,IsDuplicate\n")

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                records.forEach { r ->
                    val dateStr = sdf.format(Date(r.timestamp))
                    file.appendText("${r.timestamp},$dateStr,${r.co2Level},${r.deviceId},${r.deviceModel},${r.osVersion},${r.isDuplicate}\n")
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Експортовано:\n${file.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Помилка експорту: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}