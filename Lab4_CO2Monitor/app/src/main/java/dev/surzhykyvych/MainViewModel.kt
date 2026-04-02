package dev.surzhykyvych

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

data class Stats(
    val count: Int,
    val min: Float,
    val max: Float,
    val avg: Float
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
    }

    private val dao = AppDatabase.getDatabase(application).co2Dao()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentCo2 = MutableStateFlow(400f)
    val currentCo2: StateFlow<Float> = _currentCo2.asStateFlow()

    private val _updateIntervalMs = MutableStateFlow(5000L)

    val currentFilterTime = MutableStateFlow(System.currentTimeMillis() - HOUR)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recordsFlow: Flow<List<Co2Record>> = currentFilterTime.flatMapLatest { time ->
        dao.getRecordsSinceFlow(time)
    }

    init {
        cleanupOldData()
    }

    fun toggleRecording() {
        _isRecording.value = !_isRecording.value
        if (_isRecording.value) {
            startSimulationLoop()
        }
    }

    fun setInterval(seconds: Long) {
        if (seconds > 0) {
            _updateIntervalMs.value = seconds * 1000L
        }
    }

    private fun startSimulationLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (_isRecording.value) {
                var newCo2 = _currentCo2.value + (Random.nextFloat() * 50 - 25)
                if (newCo2 < 400f) newCo2 = 400f
                if (newCo2 > 2000f) newCo2 = 2000f

                _currentCo2.value = newCo2

                dao.insert(Co2Record(timestamp = System.currentTimeMillis(), co2Level = newCo2))
                cleanupOldData()

                delay(_updateIntervalMs.value)
            }
        }
    }

    private fun cleanupOldData() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteOldRecords(System.currentTimeMillis() - DAY)
        }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAll()
        }
    }

    suspend fun getRecordsForExport(since: Long): List<Co2Record> {
        return dao.getRecordsSinceList(since)
    }

    suspend fun getStats(): Stats {
        return Stats(
            count = dao.getCount(),
            min = dao.getMin() ?: 0f,
            max = dao.getMax() ?: 0f,
            avg = dao.getAvg() ?: 0f
        )
    }
}