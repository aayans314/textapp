package app.textapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.textapp.AppServices
import app.textapp.BuildConfig
import app.textapp.data.SessionState
import app.textapp.ui.CarbonCharcoal
import app.textapp.ui.IndustrialGray
import app.textapp.ui.SteelShadow
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.Avatar
import app.textapp.ui.components.toast
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onLoggedOut: () -> Unit) {
    val sessionState by AppServices.session.state.collectAsState(initial = SessionState())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val username = sessionState.username ?: ""

    Column(
        Modifier
            .fillMaxSize()
            .background(VoidBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenHeader("Settings")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(username, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TechSilver)
                Text("End-to-end encrypted", style = MaterialTheme.typography.bodySmall, color = IndustrialGray)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(CarbonCharcoal, MaterialTheme.shapes.medium)
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = TechSilver, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "Your chats are private",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TechSilver,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Messages and media are encrypted on your device before leaving it. The server only ever stores ciphertext, and even push notifications are decrypted locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = IndustrialGray,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = SteelShadow)
        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    AppServices.ws.stop()
                    AppServices.session.logout()
                    AppServices.pendingPassword = null
                    toast(context, "Signed out")
                    onLoggedOut()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("Sign out", color = MaterialTheme.colorScheme.error)
        }
    }
}
