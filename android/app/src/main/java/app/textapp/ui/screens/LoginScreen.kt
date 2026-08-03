package app.textapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.textapp.AppServices
import app.textapp.data.ApiException
import app.textapp.data.LoginRequest
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.AppTextField
import app.textapp.ui.components.PrimaryButton
import app.textapp.ui.establishSession
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNeedVerify: (String) -> Unit,
    onRegister: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Box(Modifier.fillMaxSize().background(VoidBlack).imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "TextApp",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TechSilver,
            )
            Text(
                "Private, lightweight messaging.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(36.dp))
            AppTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
                leadingIcon = Icons.Outlined.Person,
            )
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                password = true,
                leadingIcon = Icons.Outlined.Lock,
                keyboardType = KeyboardType.Password,
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton(text = "Log in", loading = loading) {
                val u = username.trim()
                if (u.length < 3 || password.isEmpty()) {
                    error = "Enter your username and password"
                    return@PrimaryButton
                }
                focusManager.clearFocus()
                scope.launch {
                    loading = true
                    error = null
                    try {
                        val resp = AppServices.api.call { it.login(LoginRequest(u, password)) }
                        AppServices.pendingPassword = password
                        establishSession(resp.token, resp.user, password, u)
                        onLoggedIn()
                    } catch (e: ApiException) {
                        if (e.serverError == "verification_required") {
                            AppServices.pendingPassword = password
                            onNeedVerify(u)
                        } else {
                            error = e.message
                        }
                    } finally {
                        loading = false
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
                Text("Create an account", color = TechSilver)
            }
        }
    }
}
