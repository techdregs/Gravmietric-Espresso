package com.example.gravimetric

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.example.gravimetric.ui.theme.EspressoDark
import com.example.gravimetric.ui.theme.LatteCream

@Composable
fun MassLineChart(
    dataPoints: List<ShotLogEntry>, // Each reading has .mass and .timestamp
    modifier: Modifier = Modifier,
){
    val bgInt = LatteCream.toArgb()
    val lineInt = EspressoDark.toArgb()
    // Bridge a traditional Android View (LineChart) in Compose
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LineChart(context).apply {
                // Customize chart appearance if needed
                description.isEnabled = false
                setDrawGridBackground(false)
                setBackgroundColor(bgInt)
                setPinchZoom(true)
                description.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                axisRight.isEnabled = false
            }
        },
        update = { chart ->
            if (dataPoints.isEmpty()) {
                chart.data = null
                chart.invalidate()
            } else {
                val now = System.currentTimeMillis()
                val sortedReadings = dataPoints.sortedBy { it.timestamp }
                // Convert dataPoints to Entries for MPAndroidChart
                val entries = sortedReadings.map { reading ->
                    val deltaMs = now - reading.timestamp // how many ms ago
                    val xValueSeconds = -(deltaMs / 1000f)
                    Entry(xValueSeconds, reading.mass)
                }


                val dataSet = LineDataSet(entries, "Mass (g)").apply {
                    color = lineInt
                    setDrawCircles(false)
                    setDrawValues(false) // Don’t show point values
                    lineWidth = 2f
                }

                chart.xAxis.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        // value is e.g. -30.0 if 30s old
                        val secondsAgo = (-value).toInt() // convert negative to a positive
                        return "T-$secondsAgo" // e.g. "T-30"
                    }
                }

                chart.xAxis.textColor = lineInt
                chart.axisLeft.textColor = lineInt

                // Set the data and refresh
                chart.data = LineData(dataSet)
                chart.invalidate()
            }
        }
    )
}
