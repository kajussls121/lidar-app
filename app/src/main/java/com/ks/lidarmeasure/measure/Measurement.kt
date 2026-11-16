package com.ks.lidarmeasure.measure

import com.ks.lidarmeasure.ar.ArPoint
import java.util.Locale

enum class MeasureUnit { METRIC, IMPERIAL }

/** Formats metres the way a tape measure reads: cm below a metre, then m; ft and in otherwise. */
object DistanceFormatter {

    private const val INCHES_PER_METRE = 39.3700787f

    fun format(meters: Float, unit: MeasureUnit, locale: Locale = Locale.getDefault()): String {
        val metres = if (meters.isNaN() || meters < 0f) 0f else meters
        return when (unit) {
            MeasureUnit.METRIC -> when {
                metres < 1f -> String.format(locale, "%.1f cm", metres * 100f)
                metres < 100f -> String.format(locale, "%.2f m", metres)
                else -> String.format(locale, "%.1f m", metres)
            }

            MeasureUnit.IMPERIAL -> {
                val totalInches = metres * INCHES_PER_METRE
                when {
                    totalInches < 12f -> String.format(locale, "%.1f in", totalInches)
                    else -> {
                        val feet = (totalInches / 12f).toInt()
                        val inches = totalInches - feet * 12f
                        when {
                            inches >= 11.95f -> String.format(locale, "%d ft", feet + 1)
                            inches < 0.05f -> String.format(locale, "%d ft", feet)
                            else -> String.format(locale, "%d ft %.1f in", feet, inches)
                        }
                    }
                }
            }
        }
    }
}

object MeasureMath {
    /** Length of the polyline through the placed points. */
    fun totalMetres(points: List<ArPoint>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += points[i - 1].distanceTo(points[i])
        }
        return total
    }
}
