package app.textapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.textapp.crypto.Crypto
import app.textapp.data.AppJson
import app.textapp.data.ChatTarget
import app.textapp.data.PayloadDto
import app.textapp.data.PushRegisterRequest
import app.textapp.work.ConnectionWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class TextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
        createNotificationChannel()
        scheduleConnectionWorker()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New message notifications"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleConnectionWorker() {
        val request = PeriodicWorkRequestBuilder<ConnectionWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "connection-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun onNewPushToken(token: String) {
        AppServices.scope.launch {
            AppServices.session.setFcmToken(token)
            if (AppServices.session.token() != null) {
                runCatching { AppServices.api.call { it.registerPush(PushRegisterRequest(token)) } }
            }
        }
    }

    fun registerFcmIfAvailable() {
        if (FirebaseApp.getApps(this).isEmpty()) return
        AppServices.scope.launch {
            if (AppServices.session.token() == null) return@launch
            val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return@launch
            AppServices.session.setFcmToken(token)
            runCatching { AppServices.api.call { it.registerPush(PushRegisterRequest(token)) } }
        }
    }

    /**
     * Decrypts the push payload locally (it never touches any third party as plaintext)
     * and posts a notification. Falls back to a generic message when keys are missing.
     */
    fun handleIncomingPush(context: Context, data: Map<String, String>) {
        if (data["t"] != "msg") return
        AppServices.scope.launch {
            val session = AppServices.session
            if (session.token() == null) return@launch
            val seed = session.seed() ?: return@launch
            val convId = data["conv"] ?: return@launch
            val senderName = data["sn"] ?: ""
            val peerName = session.convPeer(convId) ?: senderName
            val peerPub = session.peerPubKey(peerName)
            var text = "New message"
            if (peerPub != null) {
                try {
                    val priv = Crypto.privateKeyFromSeed(seed)
                    val key = Crypto.conversationKey(priv, Crypto.decodeB64(peerPub), convId)
                    val plain = Crypto.decrypt(key, data["ct"] ?: "")
                    val content = AppJson.decodeFromString<PayloadDto>(plain.decodeToString())
                    text = when (content.t) {
                        "text" -> content.text ?: "New message"
                        "media" -> "Photo"
                        else -> "New message"
                    }
                } catch (e: Exception) {
                    // keep the generic text
                }
            }
            postNotification(context, convId, senderName.ifEmpty { peerName }, text)
        }
    }

    private fun postNotification(context: Context, convId: String, sender: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("convId", convId)
            putExtra("peerName", sender)
        }
        val pending = PendingIntent.getActivity(
            context,
            convId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(convId.hashCode(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "messages"
    }
}
