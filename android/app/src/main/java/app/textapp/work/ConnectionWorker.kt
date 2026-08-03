package app.textapp.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.textapp.AppServices
import app.textapp.data.PushRegisterRequest

/**
 * Periodic keep-alive: mobile OSes kill idle websockets, so this re-connects,
 * re-registers the push token, and re-syncs chat state in the background.
 */
class ConnectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = AppServices.session
        if (session.token() == null) return Result.success()
        AppServices.ws.start()
        runCatching { AppServices.repository.refreshConversations() }
        runCatching { AppServices.repository.refreshFriends() }
        val fcm = session.fcmToken()
        if (fcm != null) runCatching { AppServices.api.call { it.registerPush(PushRegisterRequest(fcm)) } }
        return Result.success()
    }
}
