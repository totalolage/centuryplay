package com.airplay.streamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.airplay.streamer.MainActivity
import com.airplay.streamer.R
import com.airplay.streamer.raop.RaopCapabilities
import com.airplay.streamer.raop.RaopClient
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Foreground service that captures system audio using MediaProjection/AudioPlaybackCapture
 * and streams it to an AirPlay speaker via RAOP
 */
class AudioCaptureService : Service() {
    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_streaming"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4 // 16-bit stereo = 4 bytes
        private const val BUFFER_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME

        const val ACTION_START = "com.airplay.streamer.START"
        const val ACTION_STOP = "com.airplay.streamer.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_FEATURES = "device_features"

        // Singleton for accessing streaming state
        var instance: AudioCaptureService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var raopClient: RaopClient? = null
    private var captureJob: Job? = null
    private var startupJob: Job? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var isCapturing = false
    private var isStarting = false
    private var foregroundStarted = false
    private var cleanupInProgress = false
    private var deviceName: String = "AirPlay Speaker"

    private val mainHandler = Handler(Looper.getMainLooper())

    // Callback for UI updates
    var onStateChanged: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "AirPlay Speaker"
                val featuresJson = intent.getStringExtra(EXTRA_DEVICE_FEATURES) ?: ""

                if (resultData != null) {
                    startCapture(resultCode, resultData, host, port, featuresJson)
                } else {
                    reportFailure("MediaProjection consent result is missing")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, host: String, port: Int, featuresJson: String) {
        if (isCapturing || isStarting) return
        isStarting = true

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            foregroundStarted = true
            LogServer.log("AudioCaptureService foreground type: mediaProjection")
        } catch (e: Exception) {
            reportFailure("Foreground-service startup failed", e)
            isStarting = false
            stopSelf()
            return
        }

        startupJob = serviceScope.launch {
            try {
                // The consent result is single-use on modern Android. This service only
                // receives it once per start and never stores it for a later session.
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                val projection = try {
                    projectionManager.getMediaProjection(resultCode, resultData)
                } catch (e: Exception) {
                    reportFailure("MediaProjection initialization failed", e)
                    stopCapture()
                    stopSelf()
                    return@launch
                }

                if (projection == null) {
                    reportFailure("MediaProjection initialization failed: token was null")
                    stopCapture()
                    stopSelf()
                    return@launch
                }
                mediaProjection = projection
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        LogServer.log("MediaProjection stopped by Android")
                        stopCapture(projectionAlreadyStopped = true)
                        stopSelf()
                    }
                }
                projectionCallback = callback
                try {
                    projection.registerCallback(callback, mainHandler)
                } catch (e: Exception) {
                    reportFailure("MediaProjection initialization failed", e)
                    stopCapture()
                    stopSelf()
                    return@launch
                }

                val deviceFeatures = featuresJson.split(";")
                    .mapNotNull { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to parts[1] else null
                    }.toMap()

                if (RaopCapabilities.requiresUnsupportedFairPlay(deviceFeatures)) {
                    LogServer.log(getString(R.string.fairplay_required_message, deviceName))
                    stopCapture()
                    stopSelf()
                    return@launch
                }

                // Build and start playback capture before opening RAOP. This makes an
                // AudioRecord failure explicit and avoids opening a receiver we cannot feed.
                if (!tryAudioPlaybackCapture()) {
                    stopCapture()
                    stopSelf()
                    return@launch
                }
                if (!isStarting) return@launch

                val protocol = if (RaopCapabilities.requiresMfiAuthSetup(deviceFeatures)) {
                    "RAOP compatibility (MFiSAP auth-setup)"
                } else {
                    "AirPlay 1 (RAOP)"
                }
                LogServer.log("Protocol: $protocol")
                LogServer.log("Starting $protocol connection to $host:$port")

                raopClient = RaopClient(host, port, deviceFeatures)
                
                // Set callback to handle server disconnects
                raopClient?.callback = object : RaopClient.StreamingCallback {
                    override fun onConnected() {
                        LogServer.log("RAOP callback: Connected")
                    }
                    
                    override fun onDisconnected() {
                        LogServer.log("RAOP callback: Server disconnected - stopping service")
                        // Post to main thread to stop the service
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            stopCapture()
                            stopSelf()
                        }
                    }
                    
                    override fun onError(error: String) {
                        LogServer.log("RAOP callback: Error - $error")
                    }
                }
                
                val connected = raopClient?.connect() ?: false

                if (!connected) {
                    reportFailure("AirPlay/RAOP connection failed")
                    stopCapture()
                    stopSelf()
                    return@launch
                }

                LogServer.log("RAOP connection established, setting initial volume")
                // Set initial volume
                raopClient?.setVolume(0.8f)

                if (!isStarting) return@launch
                isStarting = false
                isCapturing = true
                onStateChanged?.invoke(true)
                LogServer.log("Audio capture started, beginning stream loop")
                startAudioStreamLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("Streaming startup failed", e)
                stopCapture()
                stopSelf()
            } finally {
                if (startupJob === coroutineContext[Job]) startupJob = null
            }
        }
    }

    private fun tryAudioPlaybackCapture(): Boolean {
        val projection = mediaProjection
        if (projection == null) {
            reportFailure("Playback capture failed: MediaProjection is unavailable")
            return false
        }

        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                BUFFER_SIZE * 4
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    reportFailure("AudioRecord initialization failed: recording did not start")
                    audioRecord?.release()
                    audioRecord = null
                    return false
                }
                true
            } else {
                reportFailure("AudioRecord initialization failed: state=${audioRecord?.state}")
                audioRecord?.release()
                audioRecord = null
                false
            }
        } catch (e: Exception) {
            reportFailure("Playback capture initialization failed", e)
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    private fun startAudioStreamLoop() {
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                while (isActive && isCapturing) {
                    val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

                    if (bytesRead > 0) {
                        raopClient?.streamAudio(buffer.copyOf(bytesRead))
                    } else if (bytesRead < 0) {
                        reportFailure("Playback capture read failed: $bytesRead")
                        mainHandler.post {
                            if (isCapturing) {
                                stopCapture()
                                stopSelf()
                            }
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("RAOP audio streaming failed", e)
                mainHandler.post {
                    if (isCapturing) {
                        stopCapture()
                        stopSelf()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun stopCapture(projectionAlreadyStopped: Boolean = false) {
        if (cleanupInProgress) return
        if (!isCapturing && !isStarting && mediaProjection == null &&
            audioRecord == null && raopClient == null && captureJob == null && !foregroundStarted) {
            return
        }
        cleanupInProgress = true
        LogServer.log("stopCapture() called - cleaning up")

        // Pause media playback so audio doesn't continue on phone speaker
        if (isCapturing) pauseMediaPlayback()

        isStarting = false
        isCapturing = false

        startupJob?.cancel()
        startupJob = null

        // Cancel the capture job
        captureJob?.cancel()
        captureJob = null

        // Stop and release audio record
        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (e: Exception) {
            LogServer.e(TAG, "Error stopping AudioRecord", e)
        }
        try {
            record?.release()
        } catch (e: Exception) {
            LogServer.e(TAG, "Error releasing AudioRecord", e)
        }

        // Disconnect clients in background to avoid blocking main thread
        // IMPORTANT: Clear callback first to prevent recursion (disconnect triggers callback -> triggers stopCapture)
        val client = raopClient
        raopClient = null
        client?.callback = null
        // Receiver teardown must outlive this service's scope, which onDestroy cancels.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                client?.disconnect()
            } catch (e: Exception) {
                LogServer.e(TAG, "Error disconnecting RAOP client", e)
            }
        }

        val projection = mediaProjection
        mediaProjection = null
        projectionCallback?.let { callback ->
            try {
                projection?.unregisterCallback(callback)
            } catch (e: Exception) {
                LogServer.e(TAG, "Error unregistering MediaProjection callback", e)
            }
        }
        projectionCallback = null

        if (projection != null && !projectionAlreadyStopped) {
            try {
                projection.stop()
                LogServer.log("MediaProjection stopped")
            } catch (e: Exception) {
                LogServer.e(TAG, "Error stopping MediaProjection", e)
            }
        }

        // Notify UI
        onStateChanged?.invoke(false)

        // Remove foreground notification
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }

        cleanupInProgress = false
        LogServer.log("stopCapture() complete")
    }

    private fun reportFailure(category: String, throwable: Throwable? = null) {
        val detail = throwable?.message?.takeIf { it.isNotBlank() }
            ?: throwable?.javaClass?.simpleName
        LogServer.e(TAG, if (detail == null) category else "$category: $detail", throwable)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirPlay Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when streaming to AirPlay speaker"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title, deviceName))
            .setContentText(getString(R.string.streaming_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_streaming), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun isCurrentlyStreaming(): Boolean = isCapturing

    /**
     * Set volume on the AirPlay speaker (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        serviceScope.launch {
            try {
                raopClient?.setVolume(volume)
                LogServer.log("Volume set to ${(volume * 100).toInt()}%")
            } catch (e: Exception) {
                LogServer.log("Failed to set volume: ${e.message}")
            }
        }
    }

    /**
     * Pause media playback using AudioManager's media key event
     * This prevents audio from suddenly playing through phone speakers after disconnect
     */
    private fun pauseMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isMusicActive) {
                // Send media pause key event
                val eventTime = android.os.SystemClock.uptimeMillis()
                val downEvent = android.view.KeyEvent(
                    eventTime, eventTime,
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0
                )
                val upEvent = android.view.KeyEvent(
                    eventTime, eventTime,
                    android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0
                )
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
                LogServer.log("Paused media playback")
            }
        } catch (e: Exception) {
            LogServer.log("Failed to pause media: ${e.message}")
        }
    }
}
