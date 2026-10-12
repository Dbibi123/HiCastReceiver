package com.example.hicastreceiver

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.app.Activity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var networkView: TextView
    private lateinit var qualityView: TextView
    private var receiver: DlnaReceiverServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentUri: String = ""
    private var prefer4k: Boolean = false
    private lateinit var qualityModeButton: Button
    private var capabilitySummary: String = ""

    private val stateTicker = object : Runnable {
        override fun run() {
            val state = when {
                player.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
                player.isPlaying -> "PLAYING"
                player.playbackState == Player.STATE_READY && !player.playWhenReady -> "PAUSED_PLAYBACK"
                else -> "STOPPED"
            }
            receiver?.updatePlaybackState(state, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.tv_status)
        networkView = findViewById(R.id.tv_network)
        qualityView = findViewById(R.id.tv_quality)
        qualityModeButton = findViewById(R.id.btn_quality_mode)

        player = ExoPlayer.Builder(this).build()
        applyQualityPolicy()
        qualityModeButton.setOnClickListener {
            prefer4k = !prefer4k
            applyQualityPolicy()
        }
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateStatusFromPlayer()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateStatusFromPlayer()
            }

            override fun onPlayerError(error: PlaybackException) {
                statusView.text = "播放失败：检查链接、编码或 DRM 支持"
                receiver?.updatePlaybackState("STOPPED", 0L, 0L)
            }
        })

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            player.stop()
            currentUri = ""
            receiver?.updatePlaybackState("STOPPED", 0L, 0L)
            statusView.text = "已停止投屏，等待新的连接"
        }

        val capability = QualityCapabilities.detect()
        capabilitySummary = capability.summary
        qualityView.text = capabilitySummary
        startDlnaReceiver()
        handler.post(stateTicker)
    }

    private fun startDlnaReceiver() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("HiCastReceiver-SSDP").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
            // SSDP may still work on devices that don't expose a Wi-Fi multicast lock.
        }

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        val stableUuid = UUID.nameUUIDFromBytes("HiCastReceiver:$androidId".toByteArray(Charsets.UTF_8)).toString()
        val service = DlnaReceiverServer(
            context = applicationContext,
            friendlyName = "HiCast 4K Receiver",
            udn = "uuid:$stableUuid",
            callbacks = object : DlnaReceiverServer.Callbacks {
                override fun onUriSet(uri: String) {
                    runOnUiThread {
                        statusView.text = "已接收投屏请求，等待播放"
                        networkView.text = "媒体地址已接收：${uri.take(110)}"
                    }
                }

                override fun onPlay(uri: String) {
                    runOnUiThread { playRemoteUri(uri) }
                }

                override fun onPause() {
                    runOnUiThread {
                        player.pause()
                        statusView.text = "已暂停"
                    }
                }

                override fun onStop() {
                    runOnUiThread {
                        player.stop()
                        currentUri = ""
                        statusView.text = "发送端已停止投屏"
                    }
                }

                override fun onSeek(positionMs: Long) {
                    runOnUiThread { player.seekTo(positionMs) }
                }
            }
        )
        receiver = service
        service.start()
        val ip = service.localAddress()
        if (ip == "0.0.0.0") {
            statusView.text = "接收服务启动异常"
            networkView.text = "未能获取局域网地址或端口 18080 被占用，请查看 logcat: HiCastDLNA"
        } else {
            statusView.text = "等待投屏连接"
            networkView.text = "电视地址：$ip · 同一局域网下打开腾讯视频或相册，点击投屏选择“HiCast 4K Receiver”"
        }
    }

    /**
     * Applies a maximum-size hint to adaptive formats. It cannot change a fixed
     * progressive MP4's encoded resolution or override a sender-controlled URL.
     */
    private fun applyQualityPolicy() {
        val width = if (prefer4k) 3840 else 1920
        val height = if (prefer4k) 2160 else 1080
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(width, height)
            .build()
        if (::qualityModeButton.isInitialized) {
            qualityModeButton.text = if (prefer4k) "目标：最高 4K" else "目标：1080p"
        }
    }

    private fun playRemoteUri(uri: String) {
        if (!uri.startsWith("http://", true) && !uri.startsWith("https://", true) && !uri.startsWith("rtsp://", true)) {
            statusView.text = "暂不支持此媒体地址类型"
            return
        }
        try {
            if (!uri.equals(currentUri, ignoreCase = false)) {
                currentUri = uri
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
            }
            player.playWhenReady = true
            player.play()
            statusView.text = "正在播放投屏媒体"
        } catch (e: Exception) {
            statusView.text = "无法打开媒体地址：${e.message ?: "未知错误"}"
        }
    }

    private fun updateStatusFromPlayer() {
        if (player.isPlaying) {
            statusView.text = "正在播放"
        } else if (player.playbackState == Player.STATE_BUFFERING) {
            statusView.text = "正在缓冲媒体…"
        } else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
            statusView.text = "已暂停"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(stateTicker)
        receiver?.stop()
        receiver = null
        playerView.player = null
        player.release()
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
        super.onDestroy()
    }
}
