package com.eloquence.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val availableVoices = arrayListOf<String>()
        val unavailableVoices = arrayListOf<String>()
        
        val supportedLocales = listOf(
            "eng-USA", "eng-GBR", "spa-ESP", "spa-MEX", "fra-FRA", "fra-CAN",
            "deu-DEU", "ita-ITA", "por-BRA", "fin-FIN", "zho-CHN", "zho-TWN",
            "jpn-JPN", "kor-KOR"
        )
        
        availableVoices.addAll(supportedLocales)
        
        val returnData = Intent()
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        finish()
    }
}
