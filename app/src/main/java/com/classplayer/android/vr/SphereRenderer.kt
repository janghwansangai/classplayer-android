package com.classplayer.android.vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 등장방형(equirectangular) 360도 영상을 구면 안쪽에 입혀 그리는 OpenGL ES 2.0 렌더러.
 * 영상 프레임은 SurfaceTexture(OES 외부 텍스처)로 받고, 카메라는 구 중심에서 yaw/pitch로 돈다.
 */
class SphereRenderer(private val onSurfaceReady: (Surface) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var yawDeg = 0f      // 카메라가 왼쪽으로 돈 각도(+)
    @Volatile var pitchDeg = 0f    // 카메라가 위를 보는 각도(+)
    @Volatile var fovDeg = 80f     // 세로 화각(핀치로 조절)

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    @Volatile private var frameAvailable = false

    private val mvp = FloatArray(16)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private var aspect = 1f

    private lateinit var vertices: FloatBuffer
    private lateinit var texCoords: FloatBuffer
    private lateinit var indices: ShortBuffer
    private var indexCount = 0

    private var aPos = 0; private var aTex = 0; private var uMvp = 0; private var uSt = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        release() // GL 문맥이 다시 만들어진 경우 예전 텍스처를 남기지 않는다
        buildSphere(rows = 48, cols = 96)
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uSt = GLES20.glGetUniformLocation(program, "uStMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId).also { it.setOnFrameAvailableListener { frameAvailable = true } }
        surfaceTexture = st
        val s = Surface(st)
        surface = s
        onSurfaceReady(s)
        Matrix.setIdentityM(stMatrix, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            frameAvailable = false
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        Matrix.perspectiveM(proj, 0, fovDeg, aspect, 0.1f, 10f)
        Matrix.setIdentityM(view, 0)
        Matrix.rotateM(view, 0, -pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(view, 0, -yawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    fun release() {
        surface?.release(); surfaceTexture?.release()
        surface = null; surfaceTexture = null
    }

    /**
     * 위도/경도 격자 구. 카메라가 -Z를 볼 때 영상 가운데(u=0.5)가 보이고,
     * 영상의 오른쪽이 화면 오른쪽에 오도록 u를 뒤집는다. v는 위가 1(SurfaceTexture 규약).
     */
    private fun buildSphere(rows: Int, cols: Int) {
        val vCount = (rows + 1) * (cols + 1)
        val pos = FloatArray(vCount * 3)
        val uv = FloatArray(vCount * 2)
        var p = 0; var t = 0
        for (i in 0..rows) {
            val theta = PI * i / rows
            val sinT = sin(theta); val cosT = cos(theta)
            for (j in 0..cols) {
                val phi = 2 * PI * j / cols
                pos[p++] = (sinT * sin(phi)).toFloat()
                pos[p++] = cosT.toFloat()
                pos[p++] = (sinT * cos(phi)).toFloat()
                uv[t++] = 1f - j.toFloat() / cols
                uv[t++] = 1f - i.toFloat() / rows
            }
        }
        val idx = ShortArray(rows * cols * 6)
        var k = 0
        for (i in 0 until rows) for (j in 0 until cols) {
            val a = (i * (cols + 1) + j).toShort()
            val b = (a + cols + 1).toShort()
            idx[k++] = a; idx[k++] = b; idx[k++] = (a + 1).toShort()
            idx[k++] = (a + 1).toShort(); idx[k++] = b; idx[k++] = (b + 1).toShort()
        }
        indexCount = idx.size
        vertices = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(pos).apply { position(0) }
        texCoords = ByteBuffer.allocateDirect(uv.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(uv).apply { position(0) }
        indices = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).apply { position(0) }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "셰이더 컴파일 실패: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val pr = GLES20.glCreateProgram()
        GLES20.glAttachShader(pr, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(pr, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(pr)
        val ok = IntArray(1); GLES20.glGetProgramiv(pr, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "프로그램 링크 실패: " + GLES20.glGetProgramInfoLog(pr) }
        return pr
    }

    companion object {
        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uStMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTex = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTexture, vTex); }
        """
    }
}
