package com.evolto.parktick

import android.app.Application
import android.content.Context
import com.evolto.parktick.data.ParkingDatabase
import com.evolto.parktick.data.ParkingRepository
import com.evolto.parktick.media.PhotoStore
import com.evolto.parktick.recognition.PlateRecognizer
import com.evolto.parktick.time.ParkingClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val photos = PhotoStore(context)
    val clock = ParkingClock(context)
    val database = ParkingDatabase.open(context)
    val repository = ParkingRepository(database, photos)
    val recognizer = PlateRecognizer(context)
}

class ParkTickApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
