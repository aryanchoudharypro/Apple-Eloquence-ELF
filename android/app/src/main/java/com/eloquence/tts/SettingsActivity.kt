package com.eloquence.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class SettingsActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        val fabTestSpeech = findViewById<ExtendedFloatingActionButton>(R.id.fab_test_speech)
        fabTestSpeech.setOnClickListener {
            testSpeech()
        }
        
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Engine is ready
            }
        }, packageName)
    }

    private fun testSpeech() {
        val testText = "This is a test of the Eloquence text to speech engine. Everything is working correctly."
        if (tts != null) {
            tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, null, "test_utterance")
        } else {
            Toast.makeText(this, "TTS engine not ready", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            findPreference<androidx.preference.Preference>("pref_about")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("About ETI Eloquence")
                    .setMessage(R.string.about_eloquence)
                    .setPositiveButton("OK", null)
                    .show()
                true
            }
            

        }
    }
}
