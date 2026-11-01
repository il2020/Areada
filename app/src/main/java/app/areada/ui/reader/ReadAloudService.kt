package app.areada.ui.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.areada.MainActivity
import app.areada.R

private const val ChannelId = "read_aloud"
private const val NotificationId = 1
private const val ExtraTitle = "title"
private const val ExtraSessionToken = "session_token"
private const val ActionStop = "app.areada.action.READ_ALOUD_STOP"

/**
 * Keeps the app in the foreground while a book is being read aloud, including pauses, so reading
 * goes on with the screen off and may take audio focus again (Android 15+ refuses it otherwise).
 * Playback itself stays in [ReadAloudViewModel]. On Android 13+ the system draws the notification's
 * controls from its media session; older versions show just the title.
 */
internal class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(ExtraTitle).orEmpty()
        val token = intent?.let { IntentCompat.getParcelableExtra(it, ExtraSessionToken, MediaSession.Token::class.java) }
        ServiceCompat.startForeground(
            this,
            NotificationId,
            notification(title, token),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    private fun notification(title: String, token: MediaSession.Token?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ChannelId, getString(R.string.read_aloud_channel), NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val openReader = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setSmallIcon(R.drawable.ic_read_aloud_notification)
            .setContentTitle(title)
            .setContentIntent(openReader)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(token))
            .build()
    }

    companion object {
        fun start(context: Context, title: String, sessionToken: MediaSession.Token) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReadAloudService::class.java)
                    .putExtra(ExtraTitle, title)
                    .putExtra(ExtraSessionToken, sessionToken),
            )
        }

        /**
         * Stops by command rather than stopService(): commands arrive in order, so this can't
         * overtake a start that still has to call startForeground(), which would crash the app.
         */
        fun stop(context: Context) {
            context.startService(Intent(context, ReadAloudService::class.java).setAction(ActionStop))
        }
    }
}
