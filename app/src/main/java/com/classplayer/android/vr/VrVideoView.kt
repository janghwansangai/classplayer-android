package com.classplayer.android.vr

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface

/**
 * 360도 영상 뷰. 한 손가락 드래그로 둘러보고, 두 손가락 핀치로 확대/축소한다.
 * Surface가 준비되면 onSurface(surface)로 알려 플레이어에 붙이게 한다.
 */
@SuppressLint("ViewConstructor")
class VrVideoView(context: Context, private val onSurface: (Surface?) -> Unit) : GLSurfaceView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val renderer = SphereRenderer { s -> main.post { onSurface(s) } }
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.fovDeg = (renderer.fovDeg / d.scaleFactor).coerceIn(30f, 110f)
            return true
        }
    })

    /** 단일 탭 콜백(컨트롤 보이기/숨기기용). */
    var onTap: (() -> Unit)? = null
    private var downTime = 0L; private var moved = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; dragging = true; moved = false; downTime = e.eventTime }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1) {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 6f) moved = true
                // 화각이 좁을수록(확대) 같은 드래그에 덜 돌도록 비례시킨다
                val k = renderer.fovDeg / height.coerceAtLeast(1)
                renderer.yawDeg += dx * k
                renderer.pitchDeg = (renderer.pitchDeg + dy * k).coerceIn(-89f, 89f)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && e.eventTime - downTime < 300) onTap?.invoke()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    fun resetView() { renderer.yawDeg = 0f; renderer.pitchDeg = 0f; renderer.fovDeg = 80f }

    override fun onDetachedFromWindow() {
        onSurface(null)
        renderer.release()
        super.onDetachedFromWindow()
    }
}
