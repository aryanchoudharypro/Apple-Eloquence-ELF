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
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.CharBuffer

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
    private val stopLock = Any()
    @Volatile private var isStopped = false
    
    // Resampler state
    private var resamplePhase: Double = 0.0
    private var lastSample: Short = 0

    private val deContext by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val ctx = createDeviceProtectedStorageContext()
            ctx.moveSharedPreferencesFrom(this, packageName + "_preferences")
            ctx
        } else {
            this
        }
    }

    override fun onCreate() {
        // Engine + eci.ini must exist before the base class wires up languages.
        writeEciIni()
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(deContext)
        val forceLang = prefs.getString("pref_language", "system") ?: "system"
        var dialectToLoad = VOICES.first().dialect
        
        if (forceLang != "system") {
            val lang = forceLang.substring(0, 3)
            val country = if (forceLang.length >= 7) forceLang.substring(4, 7) else ""
            dialectToLoad = match(lang, country)?.dialect ?: dialectToLoad
        } else {
            try {
                val locale = java.util.Locale.getDefault()
                val lang = locale.isO3Language
                val country = locale.isO3Country
                dialectToLoad = match(lang, country)?.dialect 
                    ?: match(lang, "")?.dialect 
                    ?: dialectToLoad
            } catch (e: Exception) {
                // Ignore missing resource exceptions
            }
        }
        
        ensureEngine(dialectToLoad)
        super.onCreate()
    }

    override fun onDestroy() {
        synchronized(lock) {
            synchronized(stopLock) {
                if (handle != 0L) { EloquenceNative.nativeShutdown(handle); handle = 0 }
            }
        }
        super.onDestroy()
    }

    // --- language plumbing -------------------------------------------------

    private fun match(lang: String?, country: String?): Voice? {
        if (lang == null) return null
        
        val iso3Lang = try {
            if (lang.length == 2) java.util.Locale(lang).getISO3Language() else lang
        } catch (e: Exception) { lang }
        
        val iso3Country = try {
            if (country != null && country.length == 2) java.util.Locale("", country).getISO3Country() else country
        } catch (e: Exception) { country }

        VOICES.firstOrNull { it.iso3Lang.equals(iso3Lang, ignoreCase = true) && it.iso3Country.equals(iso3Country, ignoreCase = true) }?.let { return it }
        return VOICES.firstOrNull { it.iso3Lang.equals(iso3Lang, ignoreCase = true) }
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

    override fun onGetVoices(): MutableList<android.speech.tts.Voice> {
        val voices = mutableListOf<android.speech.tts.Voice>()
        for (v in VOICES) {
            val locale = java.util.Locale(v.iso3Lang, v.iso3Country)
            voices.add(android.speech.tts.Voice(
                v.module,
                locale,
                android.speech.tts.Voice.QUALITY_VERY_HIGH,
                android.speech.tts.Voice.LATENCY_VERY_LOW,
                false,
                null
            ))
        }
        return voices
    }

    override fun onIsValidVoiceName(name: String?): Int {
        return if (VOICES.any { it.module == name }) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(name: String?): Int {
        val v = VOICES.firstOrNull { it.module == name } ?: return TextToSpeech.ERROR
        synchronized(lock) { ensureEngine(v.dialect) }
        return TextToSpeech.SUCCESS
    }

    // --- synthesis ---------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(deContext)
        val forceLang = prefs.getString("pref_language", "system") ?: "system"

        val effectiveLang = if (forceLang != "system") forceLang.substring(0, 3) else request.language
        val effectiveCountry = if (forceLang != "system" && forceLang.length >= 7) forceLang.substring(4, 7) else request.country

        val v = match(effectiveLang, effectiveCountry)
        if (v == null) { callback.error(); return }

        isStopped = false
        val text = request.charSequenceText?.toString()?.trim() ?: ""
        
        if (text.isBlank()) {
            callback.done()
            return
        }
        
        synchronized(lock) {
            
            if (!ensureEngine(v.dialect)) { callback.error(); return }
            
            val overridePitch = prefs.getBoolean("pref_override_pitch", false)
            val prefPitch = prefs.getInt("pref_pitch", 50)
            val overrideRate = prefs.getBoolean("pref_override_rate", false)
            val prefRate = prefs.getInt("pref_rate", 50)
            val volume = prefs.getInt("pref_volume", 100)
            
            val rate = if (overrideRate) {
                prefRate.coerceIn(0, 250)
            } else {
                (request.speechRate * 50 / 100).coerceIn(0, 250)
            }
            val voicePersona = prefs.getString("pref_voice", "1")?.toIntOrNull() ?: 1
            
            // Set the voice persona FIRST so we can query its natural default pitch if needed
            EloquenceNative.nativeSetVoice(handle, voicePersona)
            
            val pitchArg = if (overridePitch) {
                prefPitch.coerceIn(0, 100)
            } else {
                val basePitches = intArrayOf(65, 81, 93, 56, 89, 68, 61, 69)
                val base = if (voicePersona in 1..8) basePitches[voicePersona - 1] else 65
                (base * request.pitch / 100).coerceIn(0, 100)
            }
            
            EloquenceNative.nativeSetProsody(handle, rate, pitchArg, volume)

            val charsetName = when (currentDialect) {
                0x060000 -> "GB18030"
                0x080000 -> "Shift_JIS"
                0x0A0000 -> "EUC-KR"
                0x060001 -> "Big5"
                else -> "windows-1252"
            }
            val enableVoiceTags = prefs.getBoolean("pref_voice_tags", false)
            val stripSsml = prefs.getBoolean("pref_strip_ssml", true)
            
            var filteredText = text
            if (stripSsml) {
                // Strip XML/SSML tags safely (only tags starting with a letter or slash to protect math < and >)
                filteredText = filteredText.replace(Regex("<[a-zA-Z\\/][^>]*>"), " ").trim()
            }
            if (!enableVoiceTags) {
                filteredText = filteredText.replace("`", "")
            }
            
            if (filteredText.isBlank()) {
                callback.done()
                return
            }

            // --- Emoji Support ---
            val processEmojis = prefs.getBoolean("pref_process_emojis", true)
            if (processEmojis) {
                val sb = java.lang.StringBuilder()
                var i = 0
                val len = filteredText.length
                while (i < len) {
                    val cp = filteredText.codePointAt(i)
                    val charCount = Character.charCount(cp)
                    if (cp > 0x7F && android.icu.lang.UCharacter.hasBinaryProperty(cp, android.icu.lang.UProperty.EMOJI)) {
                        val name = android.icu.lang.UCharacter.getName(cp)
                        if (name != null) {
                            sb.append(" ").append(name.lowercase()).append(" ")
                        }
                    } else {
                        sb.appendCodePoint(cp)
                    }
                    i += charCount
                }
                filteredText = sb.toString()
            }
            
            val bytes = try {
                filteredText.toByteArray(Charset.forName(charsetName))
            } catch (e: Exception) {
                filteredText.toByteArray(Charset.forName("windows-1252"))
            }

            resamplePhase = 0.0
            lastSample = 0
            
            val targetSampleRate = prefs.getString("pref_sample_rate", "11025")?.toIntOrNull() ?: 11025
            callback.start(targetSampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            EloquenceNative.nativeStartSynthesis(handle, bytes)
        }

        val targetSampleRate = prefs.getString("pref_sample_rate", "11025")?.toIntOrNull() ?: 11025
        val shortBuf = ShortArray(4096)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(2)
        val ratio = if (targetSampleRate > 0) targetSampleRate.toDouble() / SAMPLE_RATE.toDouble() else 1.0
        val maxExpectedShorts = (shortBuf.size * ratio).toInt() + 2
        val byteBuf = ByteArray(maxOf(maxExpectedShorts * 2, maxBytes))

        while (!isStopped) {
            val n = EloquenceNative.nativePollAudio(handle, shortBuf) // This now blocks until audio is available
            if (n > 0) {
                deliverBuffer(shortBuf, n, byteBuf, maxBytes, callback, targetSampleRate)
            } else if (!EloquenceNative.nativeIsSpeaking(handle)) {
                // Synthesis finished and buffer is empty
                break
            }
        }
        
        if (isStopped) callback.error() else callback.done()
    }

    override fun onStop() {
        isStopped = true
        synchronized(stopLock) {
            if (handle != 0L) {
                EloquenceNative.nativeStop(handle)
            }
        }
    }

    private fun deliverBuffer(pcm: ShortArray, len: Int, bytes: ByteArray, maxBytes: Int, callback: SynthesisCallback, targetSampleRate: Int) {
        if (isStopped) return
        
        var outPcm = pcm
        var outLen = len
        
        // Linear interpolation resampler
        if (targetSampleRate != SAMPLE_RATE && targetSampleRate > 0) {
            val ratio = targetSampleRate.toDouble() / SAMPLE_RATE.toDouble()
            val maxOutLen = (len * ratio).toInt() + 2
            val resampled = ShortArray(maxOutLen)
            var outIdx = 0
            
            val step = SAMPLE_RATE.toDouble() / targetSampleRate.toDouble()
            var srcPhase = resamplePhase
            
            while (srcPhase < len - 1) {
                if (outIdx >= resampled.size) break
                val srcIdx = Math.floor(srcPhase).toInt()
                val frac = srcPhase - srcIdx
                val s1 = if (srcIdx < 0) lastSample else pcm[srcIdx]
                val s2 = if (srcIdx + 1 < 0) lastSample else pcm[srcIdx + 1]
                
                resampled[outIdx++] = (s1 + (s2 - s1) * frac).toInt().toShort()
                srcPhase += step
            }
            
            resamplePhase = srcPhase - len
            if (len > 0) {
                lastSample = pcm[len - 1]
            }
            
            outPcm = resampled
            outLen = outIdx
        }

        for (i in 0 until outLen) {
            if (i * 2 + 1 >= bytes.size) break
            bytes[i * 2]     = (outPcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((outPcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        var off = 0
        val totalBytes = outLen * 2
        while (off < totalBytes) {
            if (isStopped) return
            val n = minOf(maxBytes, totalBytes - off)
            try {
                if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) break
            } catch (e: Exception) {
                break
            }
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
            synchronized(stopLock) {
                EloquenceNative.nativeShutdown(handle)
                handle = 0L
            }
        }
        val newHandle = EloquenceNative.nativeInit(deContext.filesDir.absolutePath, dialect)
        if (newHandle == 0L) { Log.e(TAG, "nativeInit failed for dialect 0x%08x".format(dialect)); return false }
        synchronized(stopLock) {
            handle = newHandle
        }
        currentDialect = dialect
        return true
    }

    private fun writeEciIni() {
        val libDir = applicationInfo.nativeLibraryDir
        val sb = StringBuilder()
        sb.append("# Generated by EloquenceTtsService from assets/eci.ini\n")
        sb.append("# Path= entries point at the app's read-only native lib dir.\n\n")

        val eciIniLines = try {
            val externalDir = getExternalFilesDir(null)
            val externalIni = if (externalDir != null) File(externalDir, "eci.ini") else null
            if (externalIni != null && externalIni.exists()) {
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
                    line.startsWith("Path=") || line.startsWith("Path_Rom=") -> {
                        sb.append(line).append("\n")
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
                sb.append("Path=lib${v.module}.so\n")
                v.romanizer?.let { sb.append("Path_Rom=lib$it.so\n") }
                sb.append("Version=6.1\n\n")
            }
        }
        File(deContext.filesDir, "eci.ini").writeText(sb.toString())
    }
}
