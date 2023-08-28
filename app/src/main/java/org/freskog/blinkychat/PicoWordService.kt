package org.freskog.blinkychat

import ai.picovoice.android.voiceprocessor.VoiceProcessor
import ai.picovoice.android.voiceprocessor.VoiceProcessorFrameListener
import ai.picovoice.cheetah.Cheetah
import ai.picovoice.cheetah.CheetahException
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat


class PicoWordService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "WakeWordServiceChannel"
        private const val TAG = "PicoWordService"
    }

    private lateinit var transcriptListener: VoiceProcessorFrameListener
    private lateinit var wakeWordListener:VoiceProcessorFrameListener
    private lateinit var porcupine: Porcupine
    private lateinit var cheetah: Cheetah

    private val voiceProcessor : VoiceProcessor = VoiceProcessor.getInstance()

    override fun onCreate() {
        super.onCreate()

        val defaultKey = ApiKeys.retrieveAPIKey("picovoice_key", applicationContext)
        val sharedPref = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val apiKey = sharedPref.getString("picovoice_key", defaultKey) ?: defaultKey

        createServiceNotification()

        val transcriptBuilder:StringBuilder = StringBuilder()

        porcupine =
            Porcupine
                .Builder()
                .setAccessKey(apiKey)
                .setKeywordPaths(arrayOf("Hey-Blinky_en_android_v2_2_0.ppn"))
                .setSensitivity(0.7f)
                .build(applicationContext)
        cheetah =
            Cheetah
                .Builder()
                .setAccessKey(apiKey)
                .setModelPath("cheetah_params.pv")
                .setEndpointDuration(1f)
                .setEnableAutomaticPunctuation(true)
                .build(applicationContext)

        wakeWordListener = VoiceProcessorFrameListener { frame:ShortArray? ->
            try {
                val index = porcupine.process(frame)
                if (index == 0) {
                    sendWakeWordDetected()
                    voiceProcessor.clearFrameListeners()
                    if(porcupine.frameLength != cheetah.frameLength || porcupine.sampleRate != cheetah.sampleRate) {
                        voiceProcessor.stop()
                        voiceProcessor.start(cheetah.frameLength, cheetah.sampleRate)
                    }
                    voiceProcessor.addFrameListener(transcriptListener)
                }
            } catch (e: PorcupineException) {
                sendError(e.toString())
            }
        }

        transcriptListener = VoiceProcessorFrameListener { frame: ShortArray? ->
            try {
                val partialResult = cheetah.process(frame)
                sendPartialSpeech(partialResult.transcript)
                transcriptBuilder.append(partialResult.transcript.orEmpty())
                if (partialResult.isEndpoint) {
                    val finalResult = cheetah.flush()
                    transcriptBuilder.append(finalResult.transcript)
                    sendFullSpeech(transcriptBuilder.toString())
                    transcriptBuilder.clear()
                    voiceProcessor.clearFrameListeners()
                    if(porcupine.frameLength != cheetah.frameLength || porcupine.sampleRate != cheetah.sampleRate) {
                        voiceProcessor.stop()
                        voiceProcessor.start(porcupine.frameLength, porcupine.sampleRate)
                    }
                    voiceProcessor.addFrameListener(wakeWordListener)
                }
            } catch (e: CheetahException) {
                sendError(e.toString())
            }
        }



        voiceProcessor.addFrameListener(wakeWordListener)
        voiceProcessor.start(porcupine.frameLength, porcupine.sampleRate)

        Log.i(TAG,"voiceProcessor started for porcupine")

    }

    private fun sendError(msg:String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("Status", "ERROR")
        intent.putExtra("Message", msg)
        startActivity(intent)
        Log.e(TAG, "Sent error")
    }

    private fun sendPartialSpeech(speech:String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("Status", "PARTIAL_SPEECH")
        intent.putExtra("Speech", speech)
        startActivity(intent)
        Log.d(TAG, "Sending partial speech")
    }

    private fun sendFullSpeech(speech:String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("Status", "FULL_SPEECH")
        intent.putExtra("Speech", speech)
        startActivity(intent)
        Log.d(TAG, "Sending full speech")
    }

    private fun sendWakeWordDetected() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("Status", "WAKE_WORD_DETECTED")
        startActivity(intent)
        Log.d(TAG, "Launching activity")
    }

    private fun createServiceNotification() {

        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Blinky Service Notification",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(serviceChannel)

        // Create a notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlinkyChat")
            .setContentText("Listening for the WakeWord")
            .setSmallIcon(R.drawable.microphone_icon)
            .build()

        // Start the service in the foreground
        startForeground(NOTIFICATION_ID, notification)

    }

    override fun onDestroy() {
        Log.i(TAG,"on destroy start")

        voiceProcessor.stop()
        voiceProcessor.stop()
        porcupine.delete()
        cheetah.delete()
        super.onDestroy()
        Log.i(TAG, "porcupine, cheetah destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Return null for unbounded service
    }
}