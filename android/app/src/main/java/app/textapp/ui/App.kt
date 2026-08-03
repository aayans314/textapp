package app.textapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.textapp.AppServices
import app.textapp.data.ApiException
import app.textapp.data.SessionState
import app.textapp.ui.screens.ChatScreen
import app.textapp.ui.screens.HomeScreen
import app.textapp.ui.screens.LoginScreen
import app.textapp.ui.screens.MediaViewerScreen
import app.textapp.ui.screens.RegisterScreen
import app.textapp.ui.screens.VerifyScreen
import app.textapp.ui.components.toast
import kotlinx.coroutines.launch

@Composable
fun App() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sessionState by AppServices.session.state.collectAsState(initial = SessionState())
    val loggedIn = sessionState.token != null

    LaunchedEffect(loggedIn) {
        if (loggedIn) AppServices.startSession()
    }

    LaunchedEffect(Unit) {
        AppServices.pendingChat.collect { target ->
            if (target != null && loggedIn) {
                navController.navigate("chat/${target.conversationId}?name=${target.peerName}") {
                    launchSingleTop = true
                }
                AppServices.pendingChat.value = null
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (loggedIn) "home" else "login",
    ) {
        composable("login") {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNeedVerify = { navController.navigate("verify/$it") },
                onRegister = { navController.navigate("register") },
            )
        }
        composable("register") {
            RegisterScreen(
                onRegistered = {
                    navController.navigate("verify/$it") {
                        popUpTo("register") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            "verify/{username}",
            arguments = listOf(navArgument("username") { type = NavType.StringType }),
        ) { entry ->
            VerifyScreen(
                username = entry.arguments?.getString("username") ?: "",
                onVerified = {
                    navController.navigate("home") { popUpTo(0) }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("home") {
            HomeScreen(
                onOpenChat = { convId, name ->
                    navController.navigate("chat/$convId?name=$name") { launchSingleTop = true }
                },
                onOpenChatWithUser = { username ->
                    scope.launch {
                        try {
                            val conv = AppServices.repository.openConversation(username)
                            navController.navigate("chat/${conv.id}?name=${conv.peer.username}") {
                                launchSingleTop = true
                            }
                        } catch (e: ApiException) {
                            toast(context, e.message ?: "couldn't open chat")
                        }
                    }
                },
                onLoggedOut = {
                    navController.navigate("login") { popUpTo(0) }
                },
            )
        }
        composable(
            "chat/{convId}?name={name}",
            arguments = listOf(
                navArgument("convId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            ChatScreen(
                convId = entry.arguments?.getString("convId") ?: "",
                peerName = entry.arguments?.getString("name") ?: "",
                onBack = { navController.popBackStack() },
                onOpenMedia = { messageId ->
                    navController.navigate("media/${entry.arguments?.getString("convId")}/$messageId")
                },
            )
        }
        composable(
            "media/{convId}/{messageId}",
            arguments = listOf(
                navArgument("convId") { type = NavType.StringType },
                navArgument("messageId") { type = NavType.StringType },
            ),
        ) { entry ->
            MediaViewerScreen(
                convId = entry.arguments?.getString("convId") ?: "",
                messageId = entry.arguments?.getString("messageId") ?: "",
                onBack = { navController.popBackStack() },
            )
        }
    }
}
