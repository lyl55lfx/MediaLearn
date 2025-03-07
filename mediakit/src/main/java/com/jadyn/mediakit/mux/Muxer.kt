package com.jadyn.mediakit.mux

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import com.jadyn.mediakit.audio.AudioPacket
import com.jadyn.mediakit.camera2.VideoPacket
import com.jadyn.mediakit.function.firstSafe
import com.jadyn.mediakit.function.popSafe
import com.jadyn.mediakit.function.toS
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 *@version:
 *@FileDescription: mux模块，从H264队列和AAC队列拿去数据，编码为视频文件
 *@Author:Jing
 *@Since:2019-05-07
 *@ChangeList:
 */
class Muxer {
    private val TAG = "videoMuxer"

    //AAC 音频帧队列
    private val audioQueue by lazy {
        ConcurrentLinkedDeque<AudioPacket>()
    }
    // 视频帧队列
    private val videoQueue by lazy {
        ConcurrentLinkedDeque<VideoPacket>()
    }

    private val thread by lazy {
        val handlerThread = HandlerThread("Camera2 Muxer")
        handlerThread.start()
        handlerThread
    }

    private val handler by lazy {
        Handler(thread.looper)
    }

    private var mediaMuxer: MediaMuxer? = null

    private var loggerStream: FileOutputStream? = null

    fun start(isRecording: List<Any>, outputPath: String?,
              videoTracks: List<MediaFormat>,
              audioTracks: List<MediaFormat>) {
        handler.post {
            // 循环直到拿到可用的 输出视频轨和音频轨
            loop@ while (true) {
                if (videoTracks.isNotEmpty() && audioTracks.isNotEmpty()) {
                    start(isRecording, outputPath, videoTracks[0], audioTracks[0])
                    break@loop
                }
            }
        }
    }

    fun start(isRecording: List<Any>, outputPath: String?,
              videoTrack: MediaFormat? = null,
              audioTrack: MediaFormat? = null) {
        handler.post {
            if (mediaMuxer != null) {
                throw RuntimeException("MediaMuxer already init")
            }
            val defP = Environment.getExternalStorageDirectory().toString() + "/music${System.currentTimeMillis()}.aac"
            val p = if (outputPath.isNullOrBlank()) defP else outputPath.trim()

            val instance = Calendar.getInstance()
            val log = File(Environment.getExternalStorageDirectory().toString()
                    + "/log:${instance.get(Calendar.HOUR_OF_DAY)}" +
                    ":${instance.get(Calendar.MINUTE)}.txt")
            log.setWritable(true)
            log.createNewFile()
            loggerStream = FileOutputStream(log)

            mediaMuxer = MediaMuxer(p, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrackId = mediaMuxer!!.addTrack(videoTrack)
            val audioTrackId = mediaMuxer!!.addTrack(audioTrack)
            mediaMuxer!!.start()

            while (isRecording.isNotEmpty()) {
                val videoFrame = videoQueue.firstSafe
                val audioFrame = audioQueue.firstSafe

                val videoTime = videoFrame?.bufferInfo?.presentationTimeUs ?: -1L
                val audioTime = audioFrame?.bufferInfo?.presentationTimeUs ?: -1L

                if (videoTime == -1L && audioTime != -1L) {
                    writeAudio(audioTrackId)
                } else if (audioTime == -1L && videoTime != -1L) {
                    writeVideo(videoTrackId)
                } else if (audioTime != -1L && videoTime != -1L) {
                    // 先写小一点的时间戳的数据
                    if (audioTime < videoTime) {
                        writeAudio(audioTrackId)
                    } else {
                        writeVideo(videoTrackId)
                    }
                } else {
                    // do nothing
                }
            }
            loggerStream?.close()
            mediaMuxer!!.stop()
            mediaMuxer!!.release()
        }
    }

    private fun writeVideo(id: Int) {
        videoQueue.popSafe()?.apply {
            try {
                loggerStream?.write("video frame : ${bufferInfo?.toS()} \r\n".toByteArray())
            } catch (e: Exception) {

            }
            mediaMuxer!!.writeSampleData(id, ByteBuffer.wrap(buffer), bufferInfo)
        }
    }

    private fun writeAudio(id: Int) {
        audioQueue.popSafe()?.apply {
            try {
                loggerStream?.write("audio frame : ${bufferInfo?.toS()} \r\n".toByteArray())
            } catch (e: Exception) {

            }
            mediaMuxer!!.writeSampleData(id, ByteBuffer.wrap(buffer), bufferInfo)
        }
    }

    fun pushVideo(videoPacket: VideoPacket) {
        videoQueue.offer(videoPacket)
    }

    fun pushAudio(audioPacket: AudioPacket) {
        audioQueue.offer(audioPacket)
    }

    fun release() {
        videoQueue.clear()
        audioQueue.clear()
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        mediaMuxer = null
    }
}

