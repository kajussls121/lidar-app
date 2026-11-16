package com.ks.lidarmeasure

import com.ks.lidarmeasure.ar.ArMath
import com.ks.lidarmeasure.ar.ArPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the maths the overlay relies on: a transposed multiply or a flipped screen axis
 * would otherwise only show up on a real phone with a camera.
 */
class ArMathTest {

    @Test
    fun multiplyByIdentityLeavesTheMatrixUnchanged() {
        val translate = translation(1f, 2f, 3f)
        assertArrayEquals(translate, ArMath.multiply(identity(), translate), 0f)
        assertArrayEquals(translate, ArMath.multiply(translate, identity()), 0f)
    }

    @Test
    fun multiplyCombinesTranslations() {
        val result = ArMath.multiply(translation(1f, 0f, 1f), translation(0f, 2f, 0f))
        assertEquals(1f, result[12], 0f)
        assertEquals(2f, result[13], 0f)
        assertEquals(1f, result[14], 0f)
        assertEquals(1f, result[15], 0f)
    }

    @Test
    fun multiplyAppliesTheRightHandMatrixFirst() {
        // scale(2) * translate(2, 0, 0) translates by 4; the other order translates by 2.
        val scaleThenTranslate = ArMath.multiply(scale(2f), translation(2f, 0f, 0f))
        val translateThenScale = ArMath.multiply(translation(2f, 0f, 0f), scale(2f))
        assertEquals(4f, scaleThenTranslate[12], 0f)
        assertEquals(2f, translateThenScale[12], 0f)
    }

    @Test
    fun viewTranslationShiftsTheProjectedPoint() {
        // A camera one metre to the right of the world origin sees the origin on its left edge.
        val viewProjection = ArMath.multiply(identity(), translation(-1f, 0f, 0f))
        val out = FloatArray(2)
        assertTrue(ArMath.project(ArPoint(0f, 0f, 0f), viewProjection, 100f, 100f, out))
        assertEquals(0f, out[0], 0.001f)
        assertEquals(50f, out[1], 0.001f)
    }

    @Test
    fun worldOriginProjectsToViewportCentre() {
        val out = FloatArray(2)
        assertTrue(ArMath.project(ArPoint(0f, 0f, 0f), identity(), 1080f, 1920f, out))
        assertEquals(540f, out[0], 0.001f)
        assertEquals(960f, out[1], 0.001f)
    }

    @Test
    fun positiveWorldYProjectsTowardsTheTopOfTheScreen() {
        val out = FloatArray(2)
        assertTrue(ArMath.project(ArPoint(0f, 1f, 0f), identity(), 100f, 100f, out))
        assertEquals("a point above the camera must be near the top", 0f, out[1], 0.001f)

        assertTrue(ArMath.project(ArPoint(0f, -1f, 0f), identity(), 100f, 100f, out))
        assertEquals("a point below the camera must be near the bottom", 100f, out[1], 0.001f)
    }

    @Test
    fun positiveWorldXProjectsTowardsTheRightOfTheScreen() {
        val out = FloatArray(2)
        assertTrue(ArMath.project(ArPoint(1f, 0f, 0f), identity(), 100f, 100f, out))
        assertEquals(100f, out[0], 0.001f)

        assertTrue(ArMath.project(ArPoint(-1f, 0f, 0f), identity(), 100f, 100f, out))
        assertEquals(0f, out[0], 0.001f)
    }

    @Test
    fun pointsBehindTheCameraAreRejected() {
        // w = -z, as with a camera looking down the negative Z axis.
        val cameraLike = identity().also {
            it[11] = -1f
            it[15] = 0f
        }
        val out = FloatArray(2)
        assertTrue(ArMath.project(ArPoint(0f, 0f, -1f), cameraLike, 100f, 100f, out))
        assertFalse(ArMath.project(ArPoint(0f, 0f, 1f), cameraLike, 100f, 100f, out))
    }

    private companion object {
        /** Column major, like ARCore's matrices. */
        fun identity(): FloatArray = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        fun translation(x: Float, y: Float, z: Float): FloatArray = identity().also {
            it[12] = x
            it[13] = y
            it[14] = z
        }

        fun scale(factor: Float): FloatArray = identity().also {
            it[0] = factor
            it[5] = factor
            it[10] = factor
        }
    }
}
