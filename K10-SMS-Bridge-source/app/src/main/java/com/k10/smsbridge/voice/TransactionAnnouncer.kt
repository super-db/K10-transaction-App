package com.k10.smsbridge.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.text.NumberFormat
import java.util.Locale

class TransactionAnnouncer(context: Context) : TextToSpeech.OnInitListener {
    private val pending = ArrayDeque<String>()
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        tts.language = Locale("en", "IN")
        synchronized(pending) {
            while (pending.isNotEmpty()) speak(pending.removeFirst())
        }
    }

    fun announceReceived(amountMinor: Long) {
        val rupees = NumberFormat.getNumberInstance(Locale("en", "IN")).format(amountMinor / 100)
        val message = "Received rupees $rupees on K10 Slice Account"
        if (ready) speak(message) else synchronized(pending) { pending.addLast(message) }
    }

    private fun speak(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, "k10-${System.currentTimeMillis()}")
    }
}
