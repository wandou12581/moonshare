package com.moonshare.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GL frame gate between VirtualDisplay and the MediaCodec input surface.
 *
 * MediaProjection only produces a frame when the screen changes. That is fine for a
 * normal viewer, but a viewer joining a static screen would never receive a key frame,
 * and some encoders otherwise run at the display refresh rate (120 Hz), overloading the
 * decoder and Wi-Fi. This gate keeps the latest texture and renders it at 30 fps, so the
 * reference chain is complete and a joining viewer always gets frames.
 */
class GlFrameGate(
    private val width: Int,
    private val height: Int,
    private val encoderSurface: Surface
) {
    private val running = AtomicBoolean(false)
    private val frameLock = Object()
    private val ready = CountDownLatch(1)
    private var frameAvailable = false
    private var thread: Thread? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var program = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var matrixHandle = -1
    private var textureHandle = -1
    private var positionBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null
    private val texMatrix = FloatArray(16)

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        thread = Thread({ renderLoop() }, "MoonShare-GlGate").also {
            it.isDaemon = true
            it.start()
        }
        val ok = ready.await(4, TimeUnit.SECONDS) && inputSurface != null
        if (!ok) {
            Log.w(TAG, "GL frame gate failed to initialize")
            stop()
        }
        return ok
    }

    fun outputSurface(): Surface? = inputSurface

    fun stop() {
        running.set(false)
        synchronized(frameLock) { frameLock.notifyAll() }
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(1600)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun renderLoop() {
        try {
            if (!setupEgl()) {
                ready.countDown()
                return
            }
            ready.countDown()
            var nextFrame = System.nanoTime()
            val interval = 1_000_000_000L / TARGET_FPS
            while (running.get()) {
                synchronized(frameLock) {
                    if (!frameAvailable) frameLock.wait(4)
                }
                val fresh = synchronized(frameLock) {
                    val value = frameAvailable
                    frameAvailable = false
                    value
                }
                if (fresh) {
                    try {
                        surfaceTexture?.updateTexImage()
                    } catch (_: Exception) {
                    }
                }
                drawFrame()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                    break
                }
                nextFrame += interval
                val sleepMs = (nextFrame - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    nextFrame = System.nanoTime()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GL frame gate stopped: ${t.message}", t)
        } finally {
            cleanup()
            ready.countDown()
        }
    }

    private fun setupEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0) ||
            count[0] <= 0
        ) {
            return false
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        textureId = createTexture()
        if (textureId == 0) return false
        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }
        surfaceTexture = st
        inputSurface = Surface(st)
        GLES20.glViewport(0, 0, width, height)
        return buildProgram()
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return ids[0]
    }

    private fun buildProgram(): Boolean {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) return false
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            Log.w(TAG, "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            return false
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        positionBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        texBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        return positionHandle >= 0 && texCoordHandle >= 0
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun drawFrame() {
        surfaceTexture?.getTransformMatrix(texMatrix)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)
        positionBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        texBuffer?.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun cleanup() {
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val TAG = "MoonShareGlGate"
        private const val TARGET_FPS = 30
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
