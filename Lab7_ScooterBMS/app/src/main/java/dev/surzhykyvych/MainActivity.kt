package dev.surzhykyvych

import android.os.Bundle
import android.view.View
import android.widget.Button
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
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var screenDashboard: View
    private lateinit var screenSettings: View

    private lateinit var tvStatus: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvTemp: TextView
    private lateinit var lineChart: LineChart
    private lateinit var groupTimeFilter: MaterialButtonToggleGroup

    private lateinit var progressBattery: LinearProgressIndicator
    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvRange: TextView

    private lateinit var groupCooling: MaterialButtonToggleGroup
    private lateinit var groupCharge: MaterialButtonToggleGroup
    private lateinit var switchCharging: MaterialSwitch
    private lateinit var tvChargeTime: TextView

    private lateinit var etMaxTemp: TextInputEditText
    private lateinit var etMinVolt: TextInputEditText
    private lateinit var etMaxCurr: TextInputEditText
    private lateinit var btnSaveRules: Button
    private lateinit var btnClearDb: Button
    private lateinit var tvStatsContent: TextView

    private var isUpdatingFromCode = false
    private var chartJob: Job? = null

    private var lastAlertTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        setupNavigation()
        setupChart()
        setupControls()
        observeViewModel()

        observeChartData(5 * 60 * 1000L)
    }

    private fun initUI() {
        screenDashboard = findViewById(R.id.screenDashboard)
        screenSettings = findViewById(R.id.screenSettings)

        tvStatus = findViewById(R.id.tvStatus)
        tvVoltage = findViewById(R.id.tvVoltage)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvTemp = findViewById(R.id.tvTemp)
        lineChart = findViewById(R.id.lineChart)
        groupTimeFilter = findViewById(R.id.groupTimeFilter)

        progressBattery = findViewById(R.id.progressBattery)
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        tvRange = findViewById(R.id.tvRange)

        groupCooling = findViewById(R.id.groupCooling)
        groupCharge = findViewById(R.id.groupCharge)
        switchCharging = findViewById(R.id.switchCharging)
        tvChargeTime = findViewById(R.id.tvChargeTime)

        etMaxTemp = findViewById(R.id.etMaxTemp)
        etMinVolt = findViewById(R.id.etMinVolt)
        etMaxCurr = findViewById(R.id.etMaxCurr)
        btnSaveRules = findViewById(R.id.btnSaveRules)
        btnClearDb = findViewById(R.id.btnClearDb)
        tvStatsContent = findViewById(R.id.tvStatsContent)

        etMaxTemp.setText(viewModel.maxTempLimit.toString())
        etMinVolt.setText(viewModel.minVoltLimit.toString())
        etMaxCurr.setText(viewModel.maxCurrLimit.toString())
    }

    private fun setupNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            screenDashboard.visibility = View.GONE
            screenSettings.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_dashboard -> screenDashboard.visibility = View.VISIBLE
                R.id.nav_settings -> screenSettings.visibility = View.VISIBLE
            }
            true
        }
    }

    private fun setupChart() {
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM

        lineChart.axisRight.isEnabled = true
        lineChart.axisLeft.isEnabled = true

        lineChart.axisLeft.textColor = "#EF4444".toColorInt()
        lineChart.axisRight.textColor = "#3B82F6".toColorInt()
    }

    private fun setupControls() {
        groupCooling.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingFromCode) {
                when (checkedId) {
                    R.id.btnCool0 -> viewModel.setCooling(false, 0)
                    R.id.btnCool1 -> viewModel.setCooling(true, 1)
                    R.id.btnCool2 -> viewModel.setCooling(true, 2)
                }
            }
        }

        groupCharge.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isUpdatingFromCode) {
                when (checkedId) {
                    R.id.btnCharge1 -> viewModel.setChargeMode(1)
                    R.id.btnCharge2 -> viewModel.setChargeMode(2)
                    R.id.btnCharge3 -> viewModel.setChargeMode(3)
                }
            }
        }

        switchCharging.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingFromCode) viewModel.setChargingState(isChecked)
        }

        groupTimeFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val intervalMs = when (checkedId) {
                    R.id.btnTime1h -> 60 * 60 * 1000L
                    R.id.btnTime24h -> 24 * 60 * 60 * 1000L
                    else -> 5 * 60 * 1000L
                }
                observeChartData(intervalMs)
            }
        }

        btnSaveRules.setOnClickListener {
            val t = etMaxTemp.text.toString().toFloatOrNull() ?: viewModel.maxTempLimit
            val v = etMinVolt.text.toString().toFloatOrNull() ?: viewModel.minVoltLimit
            val c = etMaxCurr.text.toString().toFloatOrNull() ?: viewModel.maxCurrLimit

            viewModel.saveRules(t, v, c)
            Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
            it.clearFocus()
        }

        btnClearDb.setOnClickListener {
            viewModel.clearLocalDatabase()
            Toast.makeText(this, "Локальну історію очищено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.isConnected.collect { connected ->
                        if (connected) {
                            tvStatus.text = getString(R.string.status_connected)
                            tvStatus.setTextColor("#10B981".toColorInt())
                        } else {
                            tvStatus.text = getString(R.string.status_disconnected)
                            tvStatus.setTextColor("#EF4444".toColorInt())
                        }
                    }
                }

                launch {
                    viewModel.scooterState.collect { state ->
                        tvVoltage.text = String.format(Locale.US, "%.1f", state.voltage)
                        tvCurrent.text = String.format(Locale.US, "%.1f", state.current)
                        tvTemp.text = String.format(Locale.US, "%.1f", state.temperature)

                        if (state.temperature > viewModel.maxTempLimit) {
                            tvTemp.setTextColor("#D32F2F".toColorInt())
                            val now = System.currentTimeMillis()
                            if (now - lastAlertTime > 5000) {
                                Snackbar.make(
                                    findViewById(android.R.id.content),
                                    "КРИТИЧНА ПОМИЛКА: Перегрів батареї!",
                                    Snackbar.LENGTH_LONG
                                )
                                    .setBackgroundTint("#D32F2F".toColorInt())
                                    .show()
                                lastAlertTime = now
                            }
                        } else {
                            tvTemp.setTextColor("#EF4444".toColorInt())
                        }

                        progressBattery.progress = state.batteryPercent
                        tvBatteryPercent.text = getString(
                            R.string.battery_status_format,
                            state.batteryPercent,
                            state.batteryHealth
                        )

                        if (state.batteryPercent < 20) {
                            progressBattery.setIndicatorColor("#EF4444".toColorInt())
                            tvBatteryPercent.setTextColor("#EF4444".toColorInt())
                        } else {
                            progressBattery.setIndicatorColor("#10B981".toColorInt())
                            tvBatteryPercent.setTextColor("#10B981".toColorInt())
                        }

                        val efficiency = if (state.current > 15) 0.7f else 1f
                        val range = (state.batteryPercent / 100f) * 45f * efficiency
                        tvRange.text = getString(R.string.range_format, range)

                        isUpdatingFromCode = true

                        switchCharging.isChecked = state.isCharging
                        if (state.isCharging) {
                            val minutes = state.chargingTimeSec / 60
                            val seconds = state.chargingTimeSec % 60
                            tvChargeTime.text =
                                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                        } else {
                            tvChargeTime.text = "Вимкнено"
                        }

                        for (i in 0 until groupCharge.childCount) {
                            groupCharge.getChildAt(i).isEnabled = state.isCharging
                        }

                        if (!state.coolingActive) groupCooling.check(R.id.btnCool0)
                        else if (state.coolingLevel == 1) groupCooling.check(R.id.btnCool1)
                        else groupCooling.check(R.id.btnCool2)

                        when (state.chargeMode) {
                            1 -> groupCharge.check(R.id.btnCharge1)
                            2 -> groupCharge.check(R.id.btnCharge2)
                            3 -> groupCharge.check(R.id.btnCharge3)
                        }

                        isUpdatingFromCode = false
                    }
                }

                launch {
                    viewModel.predictionText.collect { predText ->
                        findViewById<TextView>(R.id.tvPrediction)?.text = predText
                    }
                }
            }
        }
    }

    private fun observeChartData(intervalMs: Long) {
        chartJob?.cancel()
        chartJob = lifecycleScope.launch {
            val since = System.currentTimeMillis() - intervalMs
            viewModel.db.getRecordsSinceFlow(since).collect { records ->
                updateChart(records)
                calculateStats(records)
            }
        }
    }

    private fun calculateStats(records: List<BatteryRecord>) {
        if (records.isEmpty()) {
            tvStatsContent.text = "Немає даних за цей період"
            return
        }

        val count = records.size
        val avgTemp = records.map { it.temperature }.average()

        val sortedTemps = records.map { it.temperature }.sorted()
        val medianTemp = if (count % 2 == 0) {
            (sortedTemps[count / 2 - 1] + sortedTemps[count / 2]) / 2.0
        } else {
            sortedTemps[count / 2].toDouble()
        }

        val firstTemp = records.first().temperature
        val lastTemp = records.last().temperature
        val diff = lastTemp - firstTemp
        val trend = when {
            diff > 0.5f -> "Зростає"
            diff < -0.5f -> "Спадає"
            else -> "Стабільно"
        }

        tvStatsContent.text =
            getString(R.string.stats_format_template, count, avgTemp, medianTemp, trend)
    }

    private fun updateChart(records: List<BatteryRecord>) {
        if (records.isEmpty()) {
            lineChart.clear()
            return
        }

        val filteredRecords = if (records.size > 500) {
            val step = ceil(records.size / 500.0).toInt()
            records.filterIndexed { index, _ -> index % step == 0 }
        } else {
            records
        }

        val referenceTime = filteredRecords.first().timestamp
        val entriesTemp =
            filteredRecords.map { Entry((it.timestamp - referenceTime).toFloat(), it.temperature) }
        val entriesVolt =
            filteredRecords.map { Entry((it.timestamp - referenceTime).toFloat(), it.voltage) }

        val dataSetTemp = LineDataSet(entriesTemp, "Температура (°C)").apply {
            color = "#EF4444".toColorInt()
            setDrawCircles(false)
            lineWidth = 2f
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val dataSetVolt = LineDataSet(entriesVolt, "Напруга (V)").apply {
            color = "#3B82F6".toColorInt()
            setDrawCircles(false)
            lineWidth = 2f
            axisDependency = YAxis.AxisDependency.RIGHT
        }

        lineChart.xAxis.valueFormatter = object : ValueFormatter() {
            private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                val originalTime = value.toLong() + referenceTime
                return sdf.format(Date(originalTime))
            }
        }

        lineChart.data = LineData(dataSetTemp, dataSetVolt)
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
    }
}