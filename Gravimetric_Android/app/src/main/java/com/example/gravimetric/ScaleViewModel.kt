package com.example.gravimetric

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateListOf

data class MassReading(
    val mass: Float,
    val timestamp: Long,
    val shotInProgress: Boolean
)

data class ShotLogEntry(
    val shotId: Long,
    val timestamp: Long,    // device clock time
    val shotTimeMs: Long,   // how many ms into the shot
    val mass: Float,
    val shotInProgress: Boolean
)

class ScaleViewModel : ViewModel() {

    // Rolling Mass Display
    private val _massReadings = mutableStateListOf<MassReading>()
    val massReadings: List<MassReading> get() = _massReadings

    var shotInProgress: Boolean = false
        private set

    fun addReading(mass: Float, fromLogging: Boolean) {
        // 1) Add to the rolling 60-second list (for live chart)
        _massReadings.add(
            MassReading(
                mass = mass,
                timestamp = System.currentTimeMillis(),
                shotInProgress = shotInProgress
            )
        )
        // Remove any reading older than 60 seconds
        val cutoff = System.currentTimeMillis() - 60_000
        while (_massReadings.isNotEmpty() && _massReadings.first().timestamp < cutoff) {
            _massReadings.removeAt(0)
        }

        // 2) Also add to the shot log if we are capturing a shot
        if (fromLogging && isShotLogging) {
            addShotLogEntry(mass)
        }
    }

    // Shot Logging
    private val _shotLogEntries = mutableStateListOf<ShotLogEntry>()
    val shotLogEntries: List<ShotLogEntry> get() = _shotLogEntries

    private var currentShotId = 0L
    private var isShotLogging = false  // Are we currently logging a shot?

    private fun addShotLogEntry(mass: Float) {
        // Called only while isShotLogging == true
        val now = System.currentTimeMillis()
        val shotTimeMs = if (currentShotId == 0L) 0 else (now - currentShotId)
        _shotLogEntries.add(
            ShotLogEntry(
                shotId = currentShotId,
                timestamp = now,
                shotTimeMs = shotTimeMs,
                mass = mass,
                shotInProgress = shotInProgress
            )
        )
    }

    fun startShot() {
        shotInProgress = true
        // Begin logging a new shot
        currentShotId = System.currentTimeMillis() // or format it if you like
        isShotLogging = true
    }

    fun stopShot() {
        shotInProgress = false
    }

    fun endShotLogging() {
        isShotLogging = false
    }

    // CSV Export
    fun exportCsv(shotId: Long?): String {
        // If shotId is null, export everything; otherwise filter
        val entries = if (shotId == null) {
            _shotLogEntries
        } else {
            _shotLogEntries.filter { it.shotId == shotId }
        }

        val sb = StringBuilder()
        sb.append("ShotID,Timestamp,ShotTimeMs,Mass,ShotStatus\n")
        for (e in entries) {
            sb.append("${e.shotId},${e.timestamp},${e.shotTimeMs},${e.mass},${e.shotInProgress}\n")
        }
        return sb.toString()
    }
}
