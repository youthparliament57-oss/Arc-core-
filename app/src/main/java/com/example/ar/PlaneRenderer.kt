package com.example.ar

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders detected ARCore planes (horizontal and vertical) with a lightweight,
 * professional spatial grid and boundary highlight.
 */
class PlaneRenderer {

    private var planeProgram: Int = 0
    private var lineProgram: Int = 0

    // Plane fill shader attributes & uniforms
    private var planePositionAttrib: Int = -1
    private var planeMvpUniform: Int = -1
    private var planeFillColorUniform: Int = -1
    private var planeGridColorUniform: Int = -1
    private var planeGridSpacingUniform: Int = -1

    // Boundary line shader attributes & uniforms
    private var linePositionAttrib: Int = -1
    private var lineMvpUniform: Int = -1
    private var lineColorUniform: Int = -1

    // Reusable matrices to prevent per-frame allocations on GL thread
    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    // Reusable vertex buffers
    private var vertexBuffer: FloatBuffer? = null
    private var vertexBufferSize = 0

    // Visual styles: Subtle, professional spatial palette
    // Horizontal (Floor / Table / Desk): Refined cool spatial blue
    private val horizontalFillColor = floatArrayOf(0.18f, 0.52f, 0.92f, 0.16f)
    private val horizontalGridColor = floatArrayOf(0.35f, 0.72f, 1.00f, 0.40f)
    private val horizontalLineColor = floatArrayOf(0.45f, 0.82f, 1.00f, 0.75f)

    // Vertical (Wall / Door): Soft lavender violet
    private val verticalFillColor = floatArrayOf(0.58f, 0.38f, 0.92f, 0.16f)
    private val verticalGridColor = floatArrayOf(0.72f, 0.55f, 1.00f, 0.40f)
    private val verticalLineColor = floatArrayOf(0.82f, 0.65f, 1.00f, 0.75f)

    fun createOnGlThread() {
        // Compile Plane Fill + Grid shader program
        val planeVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, PLANE_VERTEX_SHADER_CODE)
        val planeFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, PLANE_FRAGMENT_SHADER_CODE)
        planeProgram = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, planeVertexShader)
            GLES20.glAttachShader(prog, planeFragmentShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link plane shader: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog)
                planeProgram = 0
            }
        }

        if (planeProgram != 0) {
            planePositionAttrib = GLES20.glGetAttribLocation(planeProgram, "a_Position")
            planeMvpUniform = GLES20.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
            planeFillColorUniform = GLES20.glGetUniformLocation(planeProgram, "u_FillColor")
            planeGridColorUniform = GLES20.glGetUniformLocation(planeProgram, "u_GridColor")
            planeGridSpacingUniform = GLES20.glGetUniformLocation(planeProgram, "u_GridSpacing")
        }

        // Compile Boundary Line shader program
        val lineVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, LINE_VERTEX_SHADER_CODE)
        val lineFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, LINE_FRAGMENT_SHADER_CODE)
        lineProgram = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, lineVertexShader)
            GLES20.glAttachShader(prog, lineFragmentShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link line shader: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog)
                lineProgram = 0
            }
        }

        if (lineProgram != 0) {
            linePositionAttrib = GLES20.glGetAttribLocation(lineProgram, "a_Position")
            lineMvpUniform = GLES20.glGetUniformLocation(lineProgram, "u_ModelViewProjection")
            lineColorUniform = GLES20.glGetUniformLocation(lineProgram, "u_LineColor")
        }
    }

    /**
     * Renders detected ARCore planes in 3D world space.
     */
    fun draw(
        planes: Collection<Plane>,
        camera: Camera,
        projMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        if (planeProgram == 0 || lineProgram == 0) return
        if (camera.trackingState != TrackingState.TRACKING) return

        // Set up OpenGL state for translucent plane rendering
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        for (plane in planes) {
            // Only draw actively tracked, non-subsumed planes
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }

            val polygon = plane.polygon
            if (polygon == null || polygon.remaining() < 6) {
                continue // At least 3 points required (each has x, z)
            }

            val pointCount = polygon.remaining() / 2
            val isVertical = plane.type == Plane.Type.VERTICAL

            val fillColor = if (isVertical) verticalFillColor else horizontalFillColor
            val gridColor = if (isVertical) verticalGridColor else horizontalGridColor
            val lineColor = if (isVertical) verticalLineColor else horizontalLineColor

            // Build Model-View-Projection matrix for this plane
            plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projMatrix, 0, modelViewMatrix, 0)

            // Prepare vertex coordinates: (x, 0, z) in local space
            // Total vertices for triangle fan = pointCount + 2 (center + perimeter + closing first point)
            val fanVertexCount = pointCount + 2
            val totalFloats = fanVertexCount * 3
            val buffer = getOrCreateVertexBuffer(totalFloats)
            buffer.clear()

            // Center point (local 0, 0, 0)
            buffer.put(0.0f)
            buffer.put(0.0f)
            buffer.put(0.0f)

            // Perimeter vertices from polygon (x, 0, z)
            polygon.rewind()
            var firstX = 0f
            var firstZ = 0f
            for (i in 0 until pointCount) {
                val px = polygon.get()
                val pz = polygon.get()
                if (i == 0) {
                    firstX = px
                    firstZ = pz
                }
                buffer.put(px)
                buffer.put(0.0f)
                buffer.put(pz)
            }

            // Close the fan loop
            buffer.put(firstX)
            buffer.put(0.0f)
            buffer.put(firstZ)
            buffer.position(0)

            // --- 1. Draw Subtle Translucent Fill with Geometric Grid ---
            GLES20.glUseProgram(planeProgram)
            GLES20.glUniformMatrix4fv(planeMvpUniform, 1, false, modelViewProjectionMatrix, 0)
            GLES20.glUniform4fv(planeFillColorUniform, 1, fillColor, 0)
            GLES20.glUniform4fv(planeGridColorUniform, 1, gridColor, 0)
            GLES20.glUniform1f(planeGridSpacingUniform, 0.25f) // 25cm grid spacing

            GLES20.glEnableVertexAttribArray(planePositionAttrib)
            GLES20.glVertexAttribPointer(
                planePositionAttrib,
                3,
                GLES20.GL_FLOAT,
                false,
                3 * FLOAT_SIZE,
                buffer
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, fanVertexCount)
            GLES20.glDisableVertexAttribArray(planePositionAttrib)

            // --- 2. Draw Perimeter Boundary Line ---
            GLES20.glUseProgram(lineProgram)
            GLES20.glUniformMatrix4fv(lineMvpUniform, 1, false, modelViewProjectionMatrix, 0)
            GLES20.glUniform4fv(lineColorUniform, 1, lineColor, 0)

            // Offset buffer to skip the center vertex (1 vertex * 3 floats * 4 bytes = 12 bytes)
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(linePositionAttrib)
            GLES20.glVertexAttribPointer(
                linePositionAttrib,
                3,
                GLES20.GL_FLOAT,
                false,
                3 * FLOAT_SIZE,
                buffer
            )

            GLES20.glLineWidth(3.0f)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, pointCount)
            GLES20.glDisableVertexAttribArray(linePositionAttrib)
        }

        // Restore OpenGL state
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun getOrCreateVertexBuffer(requiredFloats: Int): FloatBuffer {
        if (vertexBuffer == null || vertexBufferSize < requiredFloats) {
            val size = maxOf(requiredFloats, 256)
            vertexBuffer = ByteBuffer.allocateDirect(size * FLOAT_SIZE)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            vertexBufferSize = size
        }
        return vertexBuffer!!
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Shader compilation error (${if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"}): ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
            }
        }
    }

    companion object {
        private const val TAG = "PlaneRenderer"
        private const val FLOAT_SIZE = 4

        private const val PLANE_VERTEX_SHADER_CODE = """
            uniform mat4 u_ModelViewProjection;
            attribute vec3 a_Position;
            varying vec2 v_LocalPos;

            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
                v_LocalPos = vec2(a_Position.x, a_Position.z);
            }
        """

        private const val PLANE_FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec2 v_LocalPos;
            uniform vec4 u_FillColor;
            uniform vec4 u_GridColor;
            uniform float u_GridSpacing;

            void main() {
                // Subtle 25cm grid pattern
                vec2 coord = abs(fract(v_LocalPos / u_GridSpacing) - 0.5);
                float d = min(coord.x, coord.y);
                float gridLine = smoothstep(0.04, 0.0, d);
                vec4 col = mix(u_FillColor, u_GridColor, gridLine * 0.45);
                gl_FragColor = col;
            }
        """

        private const val LINE_VERTEX_SHADER_CODE = """
            uniform mat4 u_ModelViewProjection;
            attribute vec3 a_Position;

            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
            }
        """

        private const val LINE_FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform vec4 u_LineColor;

            void main() {
                gl_FragColor = u_LineColor;
            }
        """
    }
}
