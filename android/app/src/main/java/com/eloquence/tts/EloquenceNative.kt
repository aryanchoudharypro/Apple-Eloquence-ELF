package com.eloquence.tts

/**
 * JNI bindings for libeloquence_jni.so (which bridges to eci.so).
 */
object EloquenceNative {
    init { System.loadLibrary("eloquence_jni") }

    /** Initialize the engine. Returns a handle, or 0 on failure. */
    @JvmStatic
    external fun nativeInit(dataDir: String, dialect: Int): Long

    /** Shut down and destroy the engine. */
    @JvmStatic
    external fun nativeShutdown(handle: Long)

    /** Set synthesis parameters. Pass -1 to leave a parameter unchanged. */
    @JvmStatic
    external fun nativeSetProsody(handle: Long, rate: Int, pitch: Int, volume: Int)

    @JvmStatic external fun nativeSynthesize(handle: Long, text: ByteArray): ShortArray?
    @JvmStatic external fun nativeStartSynthesis(handle: Long, text: ByteArray)

    /** Set the voice persona (1-8). */
    @JvmStatic
    external fun nativeSetVoice(handle: Long, voice: Int)

    /** Get the current baseline pitch of the engine. */
    @JvmStatic
    external fun nativeGetPitch(handle: Long): Int

    /** Switch the engine to a new language dialect dynamically. */
    @JvmStatic
    external fun nativeSetDialect(handle: Long, dialect: Int): Boolean

    /** Check if the engine is currently synthesizing. */
    @JvmStatic
    external fun nativeIsSpeaking(handle: Long): Boolean

    /** Poll PCM audio from the engine. Returns number of shorts copied. */
    @JvmStatic
    external fun nativePollAudio(handle: Long, outBuf: ShortArray): Int

    /** Signal the engine to abort synthesis. */
    @JvmStatic
    external fun nativeStop(handle: Long)
}
