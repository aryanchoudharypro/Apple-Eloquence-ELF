/*
 * SPDX-License-Identifier: MIT
 *
 * EloquenceTtsService -- an Android TextToSpeechService backed by the converted
 * Apple Eloquence engine. Synthesis is delegated to libeloquence_jni.so, which
 * dlopens eci.so + the language modules (shipped in the app's native lib dir)
 * and returns 11025 Hz mono S16 PCM that we hand to the platform callback.
 *
 * Engine data layout at runtime:
 *   - eci.so + lib<lang>.so live in applicationInfo.nativeLibraryDir (installed
 *     read-only from app/src/main/jniLibs/arm64-v8a/, where execution is
 *     allowed -- you cannot dlopen executable code from writable app storage on
 *     modern Android).
 *   - eci.ini is generated into filesDir at first run with absolute Path=
 *     entries pointing into nativeLibraryDir; the engine reads it from cwd, so
 *     the JNI layer chdir()s to filesDir before eciNew.
 */
package com.eloquence.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File

private const val TAG = "EloquenceTts"
private const val SAMPLE_RATE = 11025

/** One shipped voice: ISO3 language/country -> ECI dialect + module file(s). */
private data class Voice(
    val iso3Lang: String,
    val iso3Country: String,
    val dialect: Int,
    val module: String,            // lib<module>.so in nativeLibraryDir
    val romanizer: String? = null, // CJK only
)

// Mirrors release.yml's eci.ini table. Modules are installed as lib<name>.so.
private val VOICES = listOf(
    Voice("eng", "USA", 0x00010000, "enu"),
    Voice("eng", "GBR", 0x00010001, "eng"),
    Voice("spa", "ESP", 0x00020000, "esp"),
    Voice("spa", "MEX", 0x00020001, "esm"),
    Voice("fra", "FRA", 0x00030000, "fra"),
    Voice("fra", "CAN", 0x00030001, "frc"),
    Voice("deu", "DEU", 0x00040000, "deu"),
    Voice("ita", "ITA", 0x00050000, "ita"),
    Voice("por", "BRA", 0x00070000, "ptb"),
    Voice("fin", "FIN", 0x00090000, "fin"),
    Voice("zho", "CHN", 0x00060000, "chs", "chsrom"),
    Voice("zho", "TWN", 0x00060001, "cht", "chtrom"),
    Voice("jpn", "JPN", 0x00080000, "jpn", "jpnrom"),
    Voice("kor", "KOR", 0x000A0000, "kor", "korrom"),
)

class EloquenceTtsService : TextToSpeechService() {

    private var handle: Long = 0
    private var currentDialect: Int = 0
    private val lock = Any()
    @Volatile private var isStopped = false

    override fun onCreate() {
        // Engine + eci.ini must exist before the base class wires up languages.
        writeEciIni()
        ensureEngine(VOICES.first().dialect)
        super.onCreate()
    }

    override fun onDestroy() {
        synchronized(lock) {
            if (handle != 0L) { EloquenceNative.nativeShutdown(handle); handle = 0 }
        }
        super.onDestroy()
    }

    // --- language plumbing -------------------------------------------------

    private fun match(lang: String?, country: String?): Voice? {
        if (lang == null) return null
        VOICES.firstOrNull { it.iso3Lang == lang && it.iso3Country == country }?.let { return it }
        return VOICES.firstOrNull { it.iso3Lang == lang } // language-only fallback
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val v = match(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return when {
            v.iso3Lang == lang && v.iso3Country == country -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        val v = VOICES.firstOrNull { it.dialect == currentDialect } ?: VOICES.first()
        return arrayOf(v.iso3Lang, v.iso3Country, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val v = match(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        synchronized(lock) { ensureEngine(v.dialect) }
        return onIsLanguageAvailable(lang, country, variant)
    }

    // --- synthesis ---------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val forceLang = prefs.getString("pref_language", "system") ?: "system"

        val effectiveLang = if (forceLang != "system") forceLang.substring(0, 3) else request.language
        val effectiveCountry = if (forceLang != "system" && forceLang.length >= 7) forceLang.substring(4, 7) else request.country

        val v = match(effectiveLang, effectiveCountry)
        if (v == null) { callback.error(); return }

        isStopped = false
        val text = request.charSequenceText?.toString().orEmpty()
        
        synchronized(lock) {
            if (!ensureEngine(v.dialect)) { callback.error(); return }
            
            val overridePitch = prefs.getBoolean("pref_override_pitch", false)
            val prefPitch = prefs.getInt("pref_pitch", 50)
            val rateMultiplier = prefs.getInt("pref_rate_multiplier", 100)
            val voicePersona = prefs.getString("pref_voice", "1")?.toIntOrNull() ?: 1
            
            // Set the voice persona FIRST so we can query its natural default pitch if needed
            EloquenceNative.nativeSetVoice(handle, voicePersona)

            val rate = (request.speechRate * 50 * rateMultiplier / 10000).coerceIn(0, 250)
            
            val pitchArg = if (overridePitch) {
                (prefPitch * request.pitch / 100).coerceIn(0, 100)
            } else {
                if (request.pitch == 100) {
                    -1
                } else {
                    val base = EloquenceNative.nativeGetPitch(handle)
                    val effectiveBase = if (base > 0) base else 65
                    (effectiveBase * request.pitch / 100).coerceIn(0, 100)
                }
            }
            
            EloquenceNative.nativeSetProsody(handle, rate, pitchArg, -1)

            val charsetName = when (currentDialect) {
                0x060000 -> "GB18030"
                0x080000 -> "Shift_JIS"
                0x0A0000 -> "EUC-KR"
                0x060001 -> "Big5"
                else -> "windows-1252"
            }
            
            val bytes = try {
                text.toByteArray(java.nio.charset.Charset.forName(charsetName))
            } catch (e: Exception) {
                text.toByteArray(java.nio.charset.Charset.forName("windows-1252"))
            }

            callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            EloquenceNative.nativeStartSynthesis(handle, bytes)
        }

        val shortBuf = ShortArray(4096)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(2)
        val byteBuf = ByteArray(maxOf(shortBuf.size * 2, maxBytes))

        while (EloquenceNative.nativeIsSpeaking(handle) && !isStopped) {
            val n = EloquenceNative.nativePollAudio(handle, shortBuf)
            if (n > 0) {
                deliverBuffer(shortBuf, n, byteBuf, maxBytes, callback)
            } else {
                Thread.sleep(10)
            }
        }
        
        // Drain remaining audio
        while (!isStopped) {
            val n = EloquenceNative.nativePollAudio(handle, shortBuf)
            if (n > 0) {
                deliverBuffer(shortBuf, n, byteBuf, maxBytes, callback)
            } else {
                break
            }
        }
        
        // Block until the native synthesis thread actually finishes exiting
        // so we don't start a new synthesis thread while the old one is still cleaning up.
        while (EloquenceNative.nativeIsSpeaking(handle)) {
            Thread.sleep(5)
        }
        
        if (isStopped) callback.error() else callback.done()
    }

    override fun onStop() {
        isStopped = true
        synchronized(lock) { if (handle != 0L) EloquenceNative.nativeStop(handle) }
    }

    private fun deliverBuffer(pcm: ShortArray, len: Int, bytes: ByteArray, maxBytes: Int, callback: SynthesisCallback) {
        if (isStopped) return
        for (i in 0 until len) {
            bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        var off = 0
        val totalBytes = len * 2
        while (off < totalBytes) {
            if (isStopped) return
            val n = minOf(maxBytes, totalBytes - off)
            if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) break
            off += n
        }
    }

    // --- engine lifecycle --------------------------------------------------

    private fun ensureEngine(dialect: Int): Boolean {
        if (handle != 0L && dialect == currentDialect) return true
        if (handle != 0L) {
            val success = EloquenceNative.nativeSetDialect(handle, dialect)
            if (success) {
                currentDialect = dialect
                return true
            }
            EloquenceNative.nativeShutdown(handle)
            handle = 0L
        }
        handle = EloquenceNative.nativeInit(filesDir.absolutePath, dialect)
        if (handle == 0L) { Log.e(TAG, "nativeInit failed for dialect 0x%08x".format(dialect)); return false }
        currentDialect = dialect
        return true
    }

    private fun writeEciIni() {
        val libDir = applicationInfo.nativeLibraryDir
        val sb = StringBuilder()
        sb.append("# Generated by EloquenceTtsService from assets/eci.ini\n")
        sb.append("# Path= entries point at the app's read-only native lib dir.\n\n")

        val eciIniLines = try {
            val externalIni = File(getExternalFilesDir(null), "eci.ini")
            if (externalIni.exists()) {
                externalIni.readLines()
            } else {
                assets.open("eci.ini").bufferedReader().readLines()
            }
        } catch (e: Exception) {
            emptyList<String>()
        }

        if (eciIniLines.isNotEmpty()) {
            for (line in eciIniLines) {
                when {
                    line.startsWith("Path=.\\") -> {
                        val module = line.substringAfter(".\\").substringBefore(".syn")
                        sb.append("Path=$libDir/lib$module.so\n")
                    }
                    line.startsWith("Path_Rom=.\\") -> {
                        val module = line.substringAfter(".\\").substringBefore(".dll")
                        sb.append("Path_Rom=$libDir/lib$module.so\n")
                    }
                    line.startsWith("Phoneme") -> {
                        // Skip Phoneme adjustments to prevent buffer overflow in 64-bit engine
                    }
                    line.startsWith("CallbackFlag") -> {
                        // Skip CallbackFlag to avoid unsupported callback messages in the engine
                    }
                    else -> sb.append(line).append("\n")
                }
            }
        } else {
            // Fallback to original minimal generation if asset is missing
            for (v in VOICES) {
                val hi = (v.dialect ushr 16) and 0xFFFF
                val lo = v.dialect and 0xFFFF
                sb.append("[$hi.$lo]\n")
                sb.append("Path=$libDir/lib${v.module}.so\n")
                v.romanizer?.let { sb.append("Path_Rom=$libDir/lib$it.so\n") }
                sb.append("Version=6.1\n\n")
            }
        }
        File(filesDir, "eci.ini").writeText(sb.toString())
    }
}
