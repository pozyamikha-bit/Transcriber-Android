package com.transcriber.app.whisper

/** JNI-обёртка над whisper.cpp (см. app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    interface Callback {
        fun onSegment(t0: Double, t1: Double, text: ByteArray)
        fun onProgress(progress: Int)
    }

    external fun loadBackends(dir: String)
    external fun systemInfo(): String
    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun cancel()
    external fun resetCancel()
    external fun transcribe(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        threads: Int,
        offset: Double,
        vadModel: String?,
        beamSize: Int,
        bestOf: Int,
        temperatureInc: Float,
        prompt: String?,
        callback: Callback,
    ): Int
    external fun detectedLanguage(ptr: Long): String
}
