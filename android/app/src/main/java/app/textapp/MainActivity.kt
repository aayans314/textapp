package app.textapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import app.textapp.data.ChatTarget
import app.textapp.ui.App
import app.textapp.ui.TextAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        intent.getStringExtra("convId")?.let { convId ->
            AppServices.pendingChat.value = ChatTarget(convId, intent.getStringExtra("peerName") ?: "")
        }
        (application as TextApp).registerFcmIfAvailable()
        setContent {
            TextAppTheme {
                App()
            }
        }
    }
}
