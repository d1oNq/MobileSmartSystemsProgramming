package dev.surzhykyvych

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
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
    private val firestore = FirebaseFirestore.getInstance()

    @SuppressLint("HardwareIds")
    private val myDeviceId =
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "Unknown"
    // private val myDeviceId = "DEVICE_" + Random.nextInt(1000, 9999)

    private val myDeviceModel = Build.MODEL
    private val myOsVersion = Build.VERSION.RELEASE

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentCo2 = MutableStateFlow(400f)
    val currentCo2: StateFlow<Float> = _currentCo2.asStateFlow()

    private val _updateIntervalMs = MutableStateFlow(5000L)
    val currentFilterTime = MutableStateFlow(System.currentTimeMillis() - HOUR)

    private var lastRecordTimestamp = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    val recordsFlow: Flow<List<Co2Record>> = currentFilterTime.flatMapLatest { time ->
        dao.getRecordsSinceFlow(time)
    }

    init {
        cleanupOldData()
        startCloudSyncListener()
    }

    fun toggleRecording() {
        _isRecording.value = !_isRecording.value
        if (_isRecording.value) {
            cleanupOldData()
            startSimulationLoop()
        }
    }

    fun setInterval(seconds: Long) {
        if (seconds > 0) _updateIntervalMs.value = seconds * 1000L
    }

    private fun startSimulationLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _isRecording.value) {
                var newCo2 = _currentCo2.value + (Random.nextFloat() * 50 - 25)
                if (newCo2 < 400f) newCo2 = 400f
                if (newCo2 > 2000f) newCo2 = 2000f
                _currentCo2.value = newCo2

                val now = System.currentTimeMillis()
                val duplicate = (now - lastRecordTimestamp) < 1000
                lastRecordTimestamp = now

                val record = Co2Record(
                    timestamp = now,
                    co2Level = newCo2,
                    deviceId = myDeviceId,
                    deviceModel = myDeviceModel,
                    osVersion = myOsVersion,
                    isDuplicate = duplicate
                )

                dao.insert(record)

                syncUnsavedRecordsBatch()

                if (Random.nextInt(100) > 80) delay(100) else delay(_updateIntervalMs.value)
            }
        }
    }

    private fun syncUnsavedRecordsBatch() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val unsynced = dao.getUnsyncedRecords()
            if (unsynced.isEmpty()) return@launch

            val batch = firestore.batch()
            val collectionRef = firestore.collection("users").document(userId).collection("records")

            for (record in unsynced) {
                val docRef = collectionRef.document("${record.timestamp}_${record.deviceId}")
                batch.set(docRef, record.copy(synced = true))
            }

            batch.commit()
                .addOnSuccessListener {
                    viewModelScope.launch(Dispatchers.IO) {
                        for (record in unsynced) {
                            dao.update(record.copy(synced = true))
                        }
                    }
                    Log.d("BMS_SYNC", "Успішно синхронізовано ${unsynced.size} записів")
                }
                .addOnFailureListener { e ->
                    Log.e("BMS_SYNC", "Помилка синхронізації (можливо офлайн). Спроба пізніше.", e)
                }
        }
    }

    private fun startCloudSyncListener() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        firestore.collection("users").document(userId).collection("records")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener

                viewModelScope.launch(Dispatchers.IO) {
                    for (doc in snapshot.documents) {
                        val timestamp = doc.getLong("timestamp") ?: continue
                        val devId = doc.getString("deviceId") ?: "Unknown"

                        if (devId != myDeviceId) {
                            val exists = dao.isRecordExists(timestamp, devId)
                            if (!exists) {
                                val co2 = doc.getDouble("co2Level")?.toFloat() ?: continue
                                val record = Co2Record(
                                    id = 0,
                                    timestamp = timestamp,
                                    co2Level = co2,
                                    deviceId = devId,
                                    deviceModel = doc.getString("deviceModel") ?: "",
                                    osVersion = doc.getString("osVersion") ?: "",
                                    synced = true,
                                    isDuplicate = doc.getBoolean("isDuplicate") ?: false
                                )
                                dao.insert(record)
                            }
                        }
                    }
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

    suspend fun getRecordsForExport(since: Long): List<Co2Record> = dao.getRecordsSinceList(since)

    suspend fun getStats(): Stats {
        return Stats(
            count = dao.getCount(),
            min = dao.getMin() ?: 0f,
            max = dao.getMax() ?: 0f,
            avg = dao.getAvg() ?: 0f
        )
    }

    fun generateFakeHistoricalData() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var tempCo2 = 600f

            val halfHourMs = 30 * 60 * 1000L
            val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L

            for (time in (now - sevenDaysMs) until now step halfHourMs) {
                tempCo2 += (Random.nextFloat() * 100 - 50)
                if (tempCo2 < 400f) tempCo2 = 400f
                if (tempCo2 > 1500f) tempCo2 = 1500f

                dao.insert(
                    Co2Record(
                        timestamp = time,
                        co2Level = tempCo2,
                        deviceId = myDeviceId,
                        deviceModel = myDeviceModel,
                        osVersion = myOsVersion,
                        synced = false,
                        isDuplicate = false
                    )
                )
            }
        }
    }
}