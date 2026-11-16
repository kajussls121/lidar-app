package com.ks.lidarmeasure.ar

import kotlin.math.sqrt

/** High level state of the AR session, surfaced to the UI. */
enum class ArStatus {
    INITIALIZING,
    SEARCHING,
    TRACKING,
    CAMERA_UNAVAILABLE,
    AR_CORE_INSTALL_REQUIRED,
    UNSUPPORTED,
    ERROR,
}

/** A point in ARCore world space, in metres. */
data class ArPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val tracked: Boolean = true,
) {
    fun distanceTo(other: ArPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * Everything the overlay needs to draw one ARCore frame. Matrices are column major,
 * exactly as handed out by ARCore's camera.
 */
class ArFrameInfo(
    val viewMatrix: FloatArray,
    val projectionMatrix: FloatArray,
    /** Size of the AR view in pixels; tap coordinates and ARCore hit tests share this space. */
    val viewportWidth: Int,
    val viewportHeight: Int,
    val displayRotationDegrees: Int,
    val status: ArStatus,
    /** Where the centre reticle lands on a real surface, if anything is there. */
    val reticleHit: ArPoint?,
    val depthEnabled: Boolean,
    val timestampNanos: Long,
)

object ArMath {
    /** out = a * b, column-major 4x4 matrices. */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    /**
     * Projects a world point through a view-projection matrix into view pixels.
     * Returns false when the point is behind the camera.
     */
    fun project(
        point: ArPoint,
        viewProjection: FloatArray,
        widthPx: Float,
        heightPx: Float,
        out: FloatArray = FloatArray(2),
    ): Boolean {
        val m = viewProjection
        val clipX = m[0] * point.x + m[4] * point.y + m[8] * point.z + m[12]
        val clipY = m[1] * point.x + m[5] * point.y + m[9] * point.z + m[13]
        val clipW = m[3] * point.x + m[7] * point.y + m[11] * point.z + m[15]
        if (clipW <= 0.0001f) return false
        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        out[0] = (ndcX * 0.5f + 0.5f) * widthPx
        out[1] = (1f - (ndcY * 0.5f + 0.5f)) * heightPx
        return true
    }
}
