package com.example.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Pure OpenGL ES 2.0 renderer for displaying the live ARCore camera feed.
 * Draws the camera external OES texture onto a full-screen quad transformed
 * according to current device display orientation and aspect ratio.
 */
class BackgroundRenderer {

    private val quadCoords: FloatBuffer
    private val quadTexCoords: FloatBuffer

    var textureId: Int = -1
        private set

    private var program: Int = 0
    private var positionAttrib: Int = -1
    private var texCoordAttrib: Int = -1
    private var textureUniform: Int = -1
    private var coordinatesTransformed = false

    init {
        val coords = floatArrayOf(
            -1.0f, -1.0f,
            +1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, +1.0f,
        )
        quadCoords = ByteBuffer.allocateDirect(coords.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }

        quadTexCoords = ByteBuffer.allocateDirect(COORDS_PER_VERTEX * 4 * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /**
     * Allocates the external OES texture and compiles the shaders.
     * Must be called on the GL thread (e.g. within onSurfaceCreated).
     */
    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
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
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val errorMsg = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                Log.e(TAG, "Failed to link background shader program: $errorMsg")
            }
        }

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "sTexture")
    }

    /**
     * Renders the AR camera feed to the screen.
     * Must be called on the GL thread (e.g. within onDrawFrame).
     */
    fun draw(frame: Frame) {
        if (!coordinatesTransformed || frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            quadTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
            coordinatesTransformed = true
        }

        if (frame.timestamp == 0L || program == 0) {
            // Camera frame not yet ready or shader not initialized
            return
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(
            positionAttrib,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            quadCoords
        )
        GLES20.glEnableVertexAttribArray(positionAttrib)

        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(
            texCoordAttrib,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            quadTexCoords
        )
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    companion object {
        private const val TAG = "BackgroundRenderer"
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 2

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val errorMsg = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                Log.e(TAG, "Failed to compile shader (type: $type): $errorMsg")
            }
            return shader
        }
    }
}
