package app.textapp.push

import app.textapp.TextApp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as TextApp).onNewPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (FirebaseApp.getApps(this).isEmpty()) return
        val data = message.data
        if (data["t"] == "msg") {
            (application as TextApp).handleIncomingPush(this, data)
        }
    }
}
