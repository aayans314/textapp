package app.textapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.textapp.AppServices
import app.textapp.data.ApiException
import app.textapp.data.UsernameRequest
import app.textapp.data.VerifyRequest
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.AppTextField
import app.textapp.ui.components.PrimaryButton
import app.textapp.ui.establishSession
import kotlinx.coroutines.launch

@Composable
fun VerifyScreen(
    username: String,
    onVerified: () -> Unit,
    onBack: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val password = AppServices.pendingPassword

    Box(Modifier.fillMaxSize().background(VoidBlack).imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = TechSilver)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "Verify your email",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TechSilver,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Enter the 6-digit code we emailed to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(20.dp))
            AppTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = "Verification code",
                keyboardType = KeyboardType.NumberPassword,
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "Verify & continue",
                loading = loading,
                enabled = code.length == 6,
            ) {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        val resp = AppServices.api.call { it.verify(VerifyRequest(username, code)) }
                        if (password == null) {
                            error = "Please log in again to continue"
                            loading = false
                            return@launch
                        }
                        establishSession(resp.token, resp.user, password, username)
                        AppServices.pendingPassword = null
                        onVerified()
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        loading = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        resending = true
                        try {
                            AppServices.api.call { it.resend(UsernameRequest(username)) }
                            error = "Code sent - check your email"
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            resending = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !resending,
            ) {
                Text(if (resending) "Sending…" else "Resend code", color = TechSilver)
            }
        }
    }
}
