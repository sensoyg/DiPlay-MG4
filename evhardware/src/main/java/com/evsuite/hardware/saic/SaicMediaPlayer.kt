package com.evsuite.hardware.saic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.Parcel
import android.view.KeyEvent

/**
 * Best-effort SAIC media player bridge for `CarPlayController`.
 *
 * Avoids toggling playback if Bluetooth/media audio is already active so existing playback is not
 * paused on CarPlay connect.
 */
object SaicMediaPlayer {
    private const val MEDIA_PKG_OLD = "com.saicmotor.service.media"
    private const val MEDIA_CLS_OLD = "com.saicmotor.service.media.MediaService"
    private const val ACT_BT_OLD = "com.saicmotor.service.media.BT_MUSIC_ACTION"
    private const val DESC_BT_OLD = "com.saicmotor.sdk.media.IBtMusicBinderInterface"
    private const val TX_BT_PLAY = 2

    @Volatile private var appContext: Context? = null
    @Volatile private var btBinder: IBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            btBinder = service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            btBinder = null
        }
    }

    fun connect(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (btBinder != null) return
        runCatching {
            val intent = Intent(ACT_BT_OLD).setClassName(MEDIA_PKG_OLD, MEDIA_CLS_OLD)
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun playPause(): Boolean {
        val ctx = appContext ?: return false
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (audio?.isMusicActive == true) return true

        val binder = btBinder
        if (binder != null && binder.isBinderAlive) {
            val switched = runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESC_BT_OLD)
                    val ok = binder.transact(TX_BT_PLAY, data, reply, 0)
                    reply.readException()
                    ok
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }.getOrDefault(false)
            if (switched) return true
        }

        val sessionResumed = runCatching {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val bt = msm?.getActiveSessions(null)?.firstOrNull { it.packageName == "com.android.bluetooth" }
            if (bt != null && bt.playbackState?.state != PlaybackState.STATE_PLAYING) {
                bt.transportControls.play()
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (sessionResumed) return true

        return runCatching {
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio != null
        }.getOrDefault(false)
    }
}

