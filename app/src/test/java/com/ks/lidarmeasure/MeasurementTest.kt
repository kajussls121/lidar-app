package com.ks.lidarmeasure

import com.ks.lidarmeasure.ar.ArPoint
import com.ks.lidarmeasure.measure.DistanceFormatter
import com.ks.lidarmeasure.measure.MeasureMath
import com.ks.lidarmeasure.measure.MeasureUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementTest {

    @Test
    fun metricUsesCentimetresBelowAMetre() {
        assertEquals("43.2 cm", DistanceFormatter.format(0.432f, MeasureUnit.METRIC, Locale.US))
        assertEquals("100.0 cm", DistanceFormatter.format(0.9999f, MeasureUnit.METRIC, Locale.US))
    }

    @Test
    fun metricUsesMetresAboveAMetre() {
        assertEquals("1.23 m", DistanceFormatter.format(1.234f, MeasureUnit.METRIC, Locale.US))
        assertEquals("123.4 m", DistanceFormatter.format(123.44f, MeasureUnit.METRIC, Locale.US))
    }

    @Test
    fun imperialUsesInchesBelowAFoot() {
        assertEquals("8.5 in", DistanceFormatter.format(0.216f, MeasureUnit.IMPERIAL, Locale.US))
    }

    @Test
    fun imperialUsesFeetAndInches() {
        assertEquals("4 ft 11.1 in", DistanceFormatter.format(1.5f, MeasureUnit.IMPERIAL, Locale.US))
        assertEquals("1 ft", DistanceFormatter.format(0.3048f, MeasureUnit.IMPERIAL, Locale.US))
    }

    @Test
    fun totalAddsEverySegment() {
        val points = listOf(
            ArPoint(0f, 0f, 0f),
            ArPoint(1f, 0f, 0f),
            ArPoint(1f, 0f, 2f),
        )
        assertEquals(3f, MeasureMath.totalMetres(points), 0.0001f)
    }

    @Test
    fun totalOfFewerThanTwoPointsIsZero() {
        assertEquals(0f, MeasureMath.totalMetres(emptyList()), 0f)
        assertEquals(0f, MeasureMath.totalMetres(listOf(ArPoint(1f, 2f, 3f))), 0f)
    }
}
