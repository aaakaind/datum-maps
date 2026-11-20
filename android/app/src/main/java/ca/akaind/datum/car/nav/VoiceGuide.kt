package ca.akaind.datum.car.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

interface Voice {
    var muted: Boolean
    fun say(text: String)
    fun shutdown()
}

/**
 * Spoken guidance through the platform text-to-speech engine, on the navigation
 * guidance audio stream so the car lowers music while it speaks.
 */
class VoiceGuide(context: Context) : Voice, TextToSpeech.OnInitListener {
    private val prefs = context.getSharedPreferences("datum", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null
    private var counter = 0

    override var muted: Boolean
        get() = prefs.getBoolean("voice_muted", false)
        set(v) {
            prefs.edit().putBoolean("voice_muted", v).apply()
            if (v) tts.stop()
        }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setAudioAttributes(attrs)
        if (tts.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) tts.language = Locale.getDefault()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { audio?.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { audio?.abandonAudioFocusRequest(focus) }
        })
        ready = true
        pending?.let { say(it) }
        pending = null
    }

    override fun say(text: String) {
        if (muted || text.isBlank()) return
        if (!ready) { pending = text; return }
        audio?.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "datum-${counter++}")
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
        audio?.abandonAudioFocusRequest(focus)
    }
}
