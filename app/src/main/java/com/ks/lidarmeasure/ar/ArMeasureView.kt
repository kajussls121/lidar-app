package com.ks.lidarmeasure.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.ks.lidarmeasure.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Camera preview plus ARCore tracking for measuring.
 *
 * Session updates and anchors stay on the GL thread; the UI sends commands and receives
 * immutable snapshots, so nothing ARCore owns is touched from two threads at once.
 */
class ArMeasureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    interface Listener {
        fun onFrame(frame: ArFrameInfo)
        fun onStatusChanged(status: ArStatus)
        fun onPointsChanged(points: List<ArPoint>)
        fun onHint(hintRes: Int)
    }

    var listener: Listener? = null

    private val backgroundRenderer = BackgroundRenderer()
    private var session: Session? = null
    private var arStarted = false
    private var installRequested = false

    @Volatile
    private var currentStatus = ArStatus.INITIALIZING

    // GL thread only.
    private val anchors = ArrayList<Anchor>()
    private val pendingTaps = ArrayDeque<FloatArray>()
    private var pendingUndo = 0
    private var pendingClear = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var geometryDirty = true
    private var renderRotationDegrees = 0
    private var depthEnabled = false
    private var publishedPoints: List<ArPoint>? = null

    private val latestFrame = AtomicReference<ArFrameInfo?>(null)
    private val frameDispatchScheduled = AtomicBoolean(false)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(Renderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Creates/resumes the ARCore session. Safe to call repeatedly. */
    fun resumeAr(activity: Activity) {
        if (arStarted) return
        if (!ensureArCoreInstalled(activity)) return

        val activeSession = try {
            session ?: Session(activity).also {
                session = it
                configure(it)
            }
        } catch (e: UnavailableException) {
            updateStatus(ArStatus.UNSUPPORTED)
            return
        }

        try {
            activeSession.resume()
        } catch (e: CameraNotAvailableException) {
            updateStatus(ArStatus.CAMERA_UNAVAILABLE)
            return
        } catch (e: UnavailableException) {
            updateStatus(ArStatus.UNSUPPORTED)
            return
        } catch (t: Throwable) {
            updateStatus(ArStatus.ERROR)
            return
        }

        renderRotationDegrees = currentRotationDegrees()
        arStarted = true
        onResume()
        queueEvent { bindSessionOnGlThread() }
        updateStatus(ArStatus.SEARCHING)
    }

    fun pauseAr() {
        if (!arStarted) return
        arStarted = false
        onPause()
        try {
            session?.pause()
        } catch (t: Throwable) {
            // Session already paused or lost; nothing to recover here.
        }
    }

    /** Releases the session; the next [resumeAr] starts from scratch. */
    fun closeAr() {
        if (arStarted) {
            arStarted = false
            onPause()
        }
        val activeSession = session ?: return
        session = null
        anchors.forEach { anchor -> runCatching { anchor.detach() } }
        anchors.clear()
        publishedPoints = null
        runCatching { activeSession.pause() }
        runCatching { activeSession.close() }
    }

    /**
     * Recovers from a failed start. The install request is reset so the Play Store prompt can
     * be raised again; otherwise a declined install leaves the card with no way forward.
     */
    fun retry(activity: Activity) {
        closeAr()
        installRequested = false
        updateStatus(ArStatus.INITIALIZING)
        resumeAr(activity)
    }

    /** Queues a new measurement point at a tap, in this view's pixel coordinates. */
    fun addPointAt(xPx: Float, yPx: Float) {
        queueEvent {
            if (pendingTaps.size < MAX_POINTS) {
                pendingTaps.addLast(floatArrayOf(xPx, yPx))
            }
        }
    }

    /** Queues a new measurement point at the on-screen reticle. */
    fun addPointAtReticle() {
        queueEvent {
            if (viewportWidth > 0 && pendingTaps.size < MAX_POINTS) {
                pendingTaps.addLast(floatArrayOf(viewportWidth / 2f, viewportHeight / 2f))
            }
        }
    }

    fun undo() {
        queueEvent { pendingUndo += 1 }
    }

    fun clearAll() {
        queueEvent { pendingClear = true }
    }

    private fun ensureArCoreInstalled(activity: Activity): Boolean {
        val arCoreApk = ArCoreApk.getInstance()
        return try {
            when (arCoreApk.requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    installRequested = false
                    true
                }

                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    updateStatus(ArStatus.AR_CORE_INSTALL_REQUIRED)
                    false
                }

                else -> {
                    updateStatus(ArStatus.UNSUPPORTED)
                    false
                }
            }
        } catch (e: UnavailableDeviceNotCompatibleException) {
            updateStatus(ArStatus.UNSUPPORTED)
            false
        } catch (e: UnavailableUserDeclinedInstallationException) {
            updateStatus(ArStatus.AR_CORE_INSTALL_REQUIRED)
            false
        } catch (e: UnavailableException) {
            updateStatus(ArStatus.AR_CORE_INSTALL_REQUIRED)
            false
        } catch (t: Throwable) {
            updateStatus(ArStatus.ERROR)
            false
        }
    }

    private fun configure(activeSession: Session) {
        val depthSupported = runCatching {
            activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        }.getOrDefault(false)

        val config = Config(activeSession).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            instantPlacementMode = Config.InstantPlacementMode.DISABLED
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        depthEnabled = config.depthMode == Config.DepthMode.AUTOMATIC
        runCatching { activeSession.configure(config) }
            .onFailure { updateStatus(ArStatus.ERROR) }
    }

    // Called on the GL thread.
    private fun bindSessionOnGlThread() {
        val activeSession = session ?: return
        if (backgroundRenderer.textureId >= 0) {
            runCatching { activeSession.setCameraTextureName(backgroundRenderer.textureId) }
        }
        geometryDirty = true
    }

    @Suppress("DEPRECATION")
    private fun currentRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            context.getSystemService(android.view.WindowManager::class.java)?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private inner class Renderer : GLSurfaceView.Renderer {

        private var lastRotation = -1
        private var lastWidth = -1
        private var lastHeight = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            try {
                backgroundRenderer.createOnGlThread()
            } catch (t: Throwable) {
                updateStatus(ArStatus.ERROR)
                return
            }
            session?.let { activeSession ->
                if (backgroundRenderer.textureId >= 0) {
                    runCatching { activeSession.setCameraTextureName(backgroundRenderer.textureId) }
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            geometryDirty = true
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val activeSession = session ?: return
            if (viewportWidth == 0 || viewportHeight == 0) return

            val rotation = currentRotationDegrees()
            if (geometryDirty || rotation != lastRotation ||
                viewportWidth != lastWidth || viewportHeight != lastHeight
            ) {
                runCatching { activeSession.setDisplayGeometry(rotation, viewportWidth, viewportHeight) }
                lastRotation = rotation
                lastWidth = viewportWidth
                lastHeight = viewportHeight
                renderRotationDegrees = rotation
                geometryDirty = false
            }

            if (backgroundRenderer.textureId >= 0) {
                runCatching { activeSession.setCameraTextureName(backgroundRenderer.textureId) }
            }

            val frame = try {
                activeSession.update()
            } catch (e: CameraNotAvailableException) {
                updateStatus(ArStatus.CAMERA_UNAVAILABLE)
                return
            } catch (t: Throwable) {
                updateStatus(ArStatus.ERROR)
                return
            }

            applyPendingCommands(frame)

            try {
                backgroundRenderer.draw(frame)
            } catch (t: Throwable) {
                // Keep the session alive; a dropped background frame is recoverable.
            }

            publish(frame)
        }
    }

    private fun applyPendingCommands(frame: Frame) {
        if (pendingClear) {
            pendingClear = false
            anchors.forEach { anchor -> runCatching { anchor.detach() } }
            anchors.clear()
            pendingUndo = 0
            pendingTaps.clear()
        }

        if (pendingUndo > 0) {
            repeat(pendingUndo) {
                if (anchors.isEmpty()) return@repeat
                val anchor = anchors.removeAt(anchors.lastIndex)
                runCatching { anchor.detach() }
            }
            pendingUndo = 0
        }

        while (pendingTaps.isNotEmpty()) {
            val tap = pendingTaps.removeFirst()
            if (anchors.size >= MAX_POINTS) {
                postHint(R.string.hint_point_limit)
                break
            }
            val hit = findHit(frame, tap[0], tap[1])
            if (hit == null) {
                postHint(R.string.hint_no_surface)
                continue
            }
            val anchor = runCatching { hit.createAnchor() }.getOrNull() ?: continue
            anchors.add(anchor)
        }
    }

    /** First hit that actually lies on something we can measure against. */
    private fun findHit(frame: Frame, xPx: Float, yPx: Float): HitResult? {
        val hits = runCatching { frame.hitTest(xPx, yPx) }.getOrNull() ?: return null
        for (hit in hits) {
            val trackable = hit.trackable
            val pose = hit.hitPose
            val usable = when (trackable) {
                is Plane -> trackable.isPoseInPolygon(pose)
                is Point -> true
                is DepthPoint -> true
                is InstantPlacementPoint -> true
                else -> false
            }
            if (usable) return hit
        }
        return null
    }

    private fun publish(frame: Frame) {
        val camera = frame.camera

        val viewMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        val projectionMatrix = FloatArray(16)
        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_CLIP_METRES, FAR_CLIP_METRES)

        val status = if (camera.trackingState == TrackingState.TRACKING) {
            ArStatus.TRACKING
        } else {
            ArStatus.SEARCHING
        }

        val points = ArrayList<ArPoint>(anchors.size)
        for (anchor in anchors) {
            val translation = runCatching { anchor.pose.translation }.getOrNull() ?: continue
            points.add(
                ArPoint(
                    x = translation[0],
                    y = translation[1],
                    z = translation[2],
                    tracked = anchor.trackingState == TrackingState.TRACKING,
                ),
            )
        }

        val reticleHit = findHit(frame, viewportWidth / 2f, viewportHeight / 2f)?.let { hit ->
            val translation = hit.hitPose.translation
            ArPoint(translation[0], translation[1], translation[2])
        }

        publishFrame(
            ArFrameInfo(
                viewMatrix = viewMatrix,
                projectionMatrix = projectionMatrix,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                displayRotationDegrees = renderRotationDegrees,
                status = status,
                reticleHit = reticleHit,
                depthEnabled = depthEnabled,
                timestampNanos = frame.timestamp,
            ),
        )
        publishPoints(points)

        when (status) {
            ArStatus.TRACKING, ArStatus.SEARCHING -> updateStatus(status)
            else -> Unit
        }
    }

    private fun publishFrame(frame: ArFrameInfo) {
        latestFrame.set(frame)
        if (frameDispatchScheduled.compareAndSet(false, true)) {
            post {
                frameDispatchScheduled.set(false)
                latestFrame.getAndSet(null)?.let { listener?.onFrame(it) }
            }
        }
    }

    private fun publishPoints(points: List<ArPoint>) {
        val previous = publishedPoints
        if (previous != null && !pointsChanged(points, previous)) return
        publishedPoints = points
        post { listener?.onPointsChanged(points) }
    }

    /** Republish only when something moved enough to matter on screen. */
    private fun pointsChanged(a: List<ArPoint>, b: List<ArPoint>): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) {
            val p = a[i]
            val q = b[i]
            if (p.tracked != q.tracked) return true
            val dx = p.x - q.x
            val dy = p.y - q.y
            val dz = p.z - q.z
            if (dx * dx + dy * dy + dz * dz > REPUBLISH_DISTANCE_SQUARED) return true
        }
        return false
    }

    private fun updateStatus(status: ArStatus) {
        if (currentStatus == status) return
        currentStatus = status
        post { listener?.onStatusChanged(status) }
    }

    private fun postHint(hintRes: Int) {
        post { listener?.onHint(hintRes) }
    }

    private companion object {
        const val MAX_POINTS = 12
        const val NEAR_CLIP_METRES = 0.05f
        const val FAR_CLIP_METRES = 100f

        /** 2 cm, squared. */
        const val REPUBLISH_DISTANCE_SQUARED = 0.0004f
    }
}
