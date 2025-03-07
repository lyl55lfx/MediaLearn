package com.jadyn.mediakit.video.encode

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.jadyn.mediakit.gl.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 *@version:
 *@FileDescription: Surface OpenGL 编码视频帧核心类
 *@Author:Jing
 *@Since:2019-05-21
 *@ChangeList:
 */
class SurfaceEncodeCore(private val width: Int, private val height: Int) {
    private val TAG = "SurfaceEncodeCore"
    private val eglEnv by lazy {
        EglEnv(width, height)
    }

    private var surfaceTexture: SurfaceTexture? = null

    private val encodeProgram by lazy {
        Texture2dProgram()
    }

    private val frameSyncObject = Object()
    private var frameAvailable: Boolean = false

    fun buildEGLSurface(surface: Surface): SurfaceTexture {
        Log.d(TAG, "build egl thread: ${Thread.currentThread().name}")
        // 构建EGL环境
        eglEnv.setUpEnv().buildWindowSurface(surface)
        val textureId = encodeProgram.genTextureId()
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setDefaultBufferSize(width, height)
        // 监听获取新的图像帧
        surfaceTexture!!.setOnFrameAvailableListener {
            synchronized(frameSyncObject) {
                if (frameAvailable) {
                    throw RuntimeException("mFrameAvailable already set, frame could be dropped")
                }
                frameAvailable = true
                frameSyncObject.notifyAll()
            }
        }
        return surfaceTexture!!
    }

    fun draw() {
        Log.d(TAG, "core draw thread ${Thread.currentThread().name}")
        awaitNewImage()
        encodeProgram.drawFrame(surfaceTexture!!, false)
    }

    fun swapData(nesc: Long) {
        eglEnv.setPresentationTime(nesc)
        eglEnv.swapBuffers()
    }

    private fun awaitNewImage() {
        val timeoutMs: Long = 2500
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(timeoutMs)
                    if (!frameAvailable) {
                        throw RuntimeException("Camera frame wait timed out")
                    }
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            }
            frameAvailable = false
        }
        checkGlError("before updateTexImage")
        surfaceTexture!!.updateTexImage()
    }

    fun release() {
        eglEnv.release()
        surfaceTexture?.release()
        surfaceTexture = null
    }
}

class SurfaceProgram1 {
    private val VERTEX_SHADER =
            """
            uniform mat4 uMVPMatrix;
            attribute vec4 vPosition;
            attribute vec2 a_texCoord;
            varying vec2 v_texCoord;
            void main() {
                gl_Position = uMVPMatrix * vPosition;
                v_texCoord = a_texCoord;
            }
            """
    private val FRAGMENT_SHADER =
            """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D s_texture;
            void main() {
                gl_FragColor = texture2D(s_texture, v_texCoord);
            }
            """
    private val VERTEX = floatArrayOf(
            1f, 1f, 0f,
            -1f, 1f, 0f,
            -1f, -1f, 0f,
            1f, -1f, 0f
    )

    private val VERTEX_INDEX = shortArrayOf(0, 1, 2, 0, 2, 3)


    private val TEX_VERTEX = floatArrayOf(
            1f, 0f,
            0f, 0f,
            0f, 1f,
            1f, 1f)

    private val texVertexBuffer: FloatBuffer
    private val vertexBuffer: FloatBuffer

    private val mPositionHandle: Int
    private val mTexCoordHandle: Int
    private val mMatrixHandle: Int
    private val mTexSamplerHandle: Int

    private var mProgram: Int

    init {
        vertexBuffer = ByteBuffer.allocate(VERTEX.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(VERTEX)
        vertexBuffer.position(0)

        texVertexBuffer = ByteBuffer.allocate(TEX_VERTEX.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(TEX_VERTEX)
        texVertexBuffer.position(0)

        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "a_texCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mTexSamplerHandle = GLES20.glGetUniformLocation(mProgram, "s_texture");

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false,
                12, vertexBuffer)

        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0,
                texVertexBuffer)
    }

    private var textureId: Int = 0

    fun genTextureId(): Int {
        textureId = buildTextureId()
        return textureId
    }

    fun drawFrame(st: SurfaceTexture){
        
    }
}


/**
 * 使用 作为数据传递介质 Surface 绘制
 *
 * 每一帧的数据从SurfaceTexture 中获取
 * */

class SurfaceProgram {
    private val FLOAT_SIZE_BYTES = 4
    private val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
    private val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
    private val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

    private var textureId: Int = -123

    //顶点着色器
    private val VERTEX_SHADER =
            """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
            """

    //片元着色器
    private val FRAGMENT_SHADER =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
            """

    private val triangleVerticesData = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f,
            0f, -1.0f, 1.0f, 0f,
            0f, 1f, 1.0f, 1.0f,
            0f, 1f, 1f)

    private val triangleVertices: FloatBuffer

    private val mMVPMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private var program: Int = 0
    private var muMVPMatrixHandle: Int = 0
    private var muSTMatrixHandle: Int = 0
    private var maPositionHandle: Int = 0
    private var maTextureHandle: Int = 0

    init {
        triangleVertices = ByteBuffer.allocateDirect(
                triangleVerticesData.size * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)

        Matrix.setIdentityM(stMatrix, 0)

        Log.d(this.javaClass.name, " create texture 2d program ")
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        checkLocation(maPositionHandle, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        checkLocation(maTextureHandle, "aTextureCoord")

        /**
         * uniform :代表从CPU传递到GPU的数据；
         * 以下就是获取特定uniform的的索引值，方便后续更新
         * 更新使用的函数是 @glUniform4f
         * */
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        checkLocation(muMVPMatrixHandle, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        checkLocation(muSTMatrixHandle, "uSTMatrix")

    }

    fun genTextureId(): Int {
        textureId = buildTextureId()
        return textureId
    }

    fun drawFrame(st: SurfaceTexture) {
        checkGlError("onDrawFrame start")
        st.getTransformMatrix(stMatrix)

        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        Matrix.setIdentityM(mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        unBindTexture()
    }
}