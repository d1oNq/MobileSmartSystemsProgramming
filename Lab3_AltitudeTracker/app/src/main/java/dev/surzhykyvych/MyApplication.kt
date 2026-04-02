package dev.surzhykyvych

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (_: Exception) {
        }
    }
}