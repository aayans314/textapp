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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.textapp.AppServices
import app.textapp.data.ApiException
import app.textapp.data.RegisterRequest
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.AppTextField
import app.textapp.ui.components.PrimaryButton
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(
    onRegistered: (String) -> Unit,
    onBack: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

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
                    "Create your account",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TechSilver,
                )
            }
            Spacer(Modifier.height(28.dp))
            AppTextField(username, { username = it }, "Username", leadingIcon = Icons.Outlined.Person)
            Spacer(Modifier.height(12.dp))
            AppTextField(email, { email = it }, "Email", leadingIcon = Icons.Outlined.Email, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            AppTextField(password, { password = it }, "Password", password = true, leadingIcon = Icons.Outlined.Lock)
            Spacer(Modifier.height(12.dp))
            AppTextField(confirm, { confirm = it }, "Confirm password", password = true, leadingIcon = Icons.Outlined.Lock)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton(text = "Sign up", loading = loading) {
                val u = username.trim()
                val e = email.trim()
                when {
                    u.length < 3 || u.length > 20 -> error = "Username must be 3-20 characters"
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches() -> error = "Enter a valid email"
                    password.length < 8 -> error = "Password must be at least 8 characters"
                    password != confirm -> error = "Passwords do not match"
                    else -> {
                        focusManager.clearFocus()
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                AppServices.api.call { it.register(RegisterRequest(u, e, password)) }
                                AppServices.pendingPassword = password
                                onRegistered(u)
                            } catch (ex: ApiException) {
                                error = ex.message
                            } finally {
                                loading = false
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "We'll email you a 6-digit code to verify your address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
