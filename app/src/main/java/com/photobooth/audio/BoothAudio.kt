package com.photobooth.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Countdown beeps, French voice cues (when a TTS engine exists) and the shutter click. */
@Singleton
class BoothAudio @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }
    private val shutterSound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    @Synchronized
    fun prepare() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val result = engine.setLanguage(Locale.FRENCH)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
            Log.i(TAG, "BoothAudio: tts ready=$ttsReady (status=$status)")
        }
    }

    fun tick(number: Int) {
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140) }
        speak(number.toString())
    }

    fun smile() {
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, 250) }
        speak("Souriez !")
    }

    fun shutter() {
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue-$text")
    }
}
