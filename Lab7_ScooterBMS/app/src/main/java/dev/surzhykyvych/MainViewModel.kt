package dev.surzhykyvych

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.content.edit

data class ScooterState(
    val timestamp: Long = System.currentTimeMillis(),
    val voltage: Float = 0f,
    val current: Float = 0f,
    val temperature: Float = 0f,
    val coolingActive: Boolean = false,
    val coolingLevel: Int = 0,
    val chargeMode: Int = 1,
    val isCharging: Boolean = true,
    val chargingTimeSec: Int = 0,
    val batteryPercent: Int = 0,
    val batteryHealth: Int = 100
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TEMP_SAFE_MARGIN = 20f
        const val TEMP_IDEAL = 25f
        const val MAX_VOLTAGE = 48f
        const val HIGH_CURRENT_LIMIT_MS = 2 * 60 * 60 * 1000L
    }

    val db = AppDatabase.getDatabase(application).batteryDao()
    private val prefs: SharedPreferences =
        application.getSharedPreferences("BmsSettings", Context.MODE_PRIVATE)

    var maxTempLimit = prefs.getFloat("max_temp", 55f)
    var minVoltLimit = prefs.getFloat("min_volt", 30f)
    var maxCurrLimit = prefs.getFloat("max_curr", 15f)

    private val firebaseDb = FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DB_URL)
    private val stateRef =
        firebaseDb.getReference("scooter/state")
    private val commandsRef =
        firebaseDb.getReference("scooter/commands")

    private val _scooterState = MutableStateFlow(ScooterState())
    val scooterState: StateFlow<ScooterState> = _scooterState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _predictionText = MutableStateFlow("Прогноз: збір даних...")
    val predictionText: StateFlow<String> = _predictionText.asStateFlow()

    private var highCurrentStartTime: Long? = null

    init {
        stateRef.keepSynced(true)
        startListeningToFirebase()
        startPredictionFlow()
        startDatabaseCleanup()
    }

    fun saveRules(temp: Float, volt: Float, curr: Float) {
        maxTempLimit = temp
        minVoltLimit = volt
        maxCurrLimit = curr
        prefs.edit {
            putFloat("max_temp", temp)
            .putFloat("min_volt", volt)
            .putFloat("max_curr", curr)
        }
    }

    private fun startListeningToFirebase() {
        stateRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _isConnected.value = true
                try {
                    val serverTimestamp =
                        snapshot.child("timestamp").getValue(Double::class.java)?.toLong()
                            ?: System.currentTimeMillis()
                    val v = snapshot.child("voltage").getValue(Double::class.java)?.toFloat() ?: 0f
                    val c = snapshot.child("current").getValue(Double::class.java)?.toFloat() ?: 0f
                    val t =
                        snapshot.child("temperature").getValue(Double::class.java)?.toFloat() ?: 0f
                    val cooling =
                        snapshot.child("cooling_active").getValue(Boolean::class.java) ?: false
                    val cLevel = snapshot.child("cooling_level").getValue(Int::class.java) ?: 0
                    val charge = snapshot.child("charge_mode").getValue(Int::class.java) ?: 1
                    val isCharging =
                        snapshot.child("is_charging").getValue(Boolean::class.java) ?: true
                    val chargingTimeSec =
                        snapshot.child("charging_time_sec").getValue(Double::class.java)?.toInt()
                            ?: 0
                    val batteryPercent =
                        snapshot.child("battery_percent").getValue(Double::class.java)?.toInt() ?: 0
                    val batteryHealth =
                        snapshot.child("battery_health").getValue(Double::class.java)?.toInt()
                            ?: 100

                    val newState = ScooterState(
                        serverTimestamp,
                        v,
                        c,
                        t,
                        cooling,
                        cLevel,
                        charge,
                        isCharging,
                        chargingTimeSec,
                        batteryPercent,
                        batteryHealth
                    )
                    _scooterState.value = newState

                    saveToLocalDb(newState)
                    checkRulesAndControl(newState)
                } catch (e: Exception) {
                    Log.e("BMS", "Помилка: ${e.message}")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                _isConnected.value = false
            }
        })
    }

    private fun startPredictionFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val since = System.currentTimeMillis() - 5 * 60 * 1000L
                val records = db.getRecordsSinceList(since)

                if (records.size >= 3) {
                    val first = records.first()
                    val last = records.last()
                    val prev = records[records.size - 2]

                    val dtMinutes = (last.timestamp - first.timestamp) / 1000f / 60f

                    if (dtMinutes > 0) {
                        val rate = (last.temperature - first.temperature) / dtMinutes
                        val loadFactor = last.current / maxCurrLimit
                        val inertia = last.temperature - prev.temperature

                        val predictedTemp =
                            last.temperature + (rate * 5f) + (loadFactor * 2f) + inertia

                        _predictionText.value = if (predictedTemp > maxTempLimit) {
                            "Прогноз (5 хв): Перегрів до %.1f°C!".format(predictedTemp)
                        } else {
                            "Прогноз (5 хв): Стабільно %.1f°C".format(predictedTemp)
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    private fun startDatabaseCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                db.deleteOldRecords(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
                delay(60 * 60 * 1000L)
            }
        }
    }

    fun clearLocalDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            db.clearAll()
        }
    }

    private fun saveToLocalDb(state: ScooterState) {
        viewModelScope.launch(Dispatchers.IO) {
            db.insert(
                BatteryRecord(
                    timestamp = state.timestamp,
                    voltage = state.voltage,
                    current = state.current,
                    temperature = state.temperature,
                    batteryHealth = state.batteryHealth
                )
            )
        }
    }

    private fun checkRulesAndControl(state: ScooterState) {
        val updates = mutableMapOf<String, Any>()

        if (state.temperature > maxTempLimit && state.coolingLevel < 2) {
            updates["cooling_active"] = true
            updates["cooling_level"] = 2
        } else if (state.temperature < (maxTempLimit - TEMP_SAFE_MARGIN) && state.coolingLevel == 2) {
            updates["cooling_active"] = true
            updates["cooling_level"] = 1
        } else if (state.temperature < TEMP_IDEAL && state.coolingActive) {
            updates["cooling_active"] = false
            updates["cooling_level"] = 0
        }

        if (state.voltage < minVoltLimit && state.chargeMode != 3 && state.isCharging) {
            updates["charge_mode"] = 3
        }

        if (state.current > maxCurrLimit && state.isCharging) {
            if (highCurrentStartTime == null) highCurrentStartTime = System.currentTimeMillis()
            else if ((System.currentTimeMillis() - highCurrentStartTime!!) >= HIGH_CURRENT_LIMIT_MS && state.chargeMode != 3) {
                updates["charge_mode"] = 3
                highCurrentStartTime = null
            }
        } else highCurrentStartTime = null

        if (state.batteryPercent >= 100 && state.isCharging) {
            updates["is_charging"] = false
        }

        if (updates.isNotEmpty()) {
            val filteredUpdates = updates.filter { (key, value) ->
                when (key) {
                    "cooling_active" -> value != state.coolingActive
                    "cooling_level" -> value != state.coolingLevel
                    "charge_mode" -> value != state.chargeMode
                    "is_charging" -> value != state.isCharging
                    else -> true
                }
            }

            if (filteredUpdates.isNotEmpty()) {
                commandsRef.updateChildren(filteredUpdates)
            }
        }
    }

    fun setCooling(active: Boolean, level: Int) =
        commandsRef.updateChildren(mapOf("cooling_active" to active, "cooling_level" to level))

    fun setChargeMode(mode: Int) = commandsRef.child("charge_mode").setValue(mode)
    fun setChargingState(active: Boolean) = commandsRef.child("is_charging").setValue(active)
}