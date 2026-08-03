package app.textapp.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.textapp.AppServices
import app.textapp.data.ResolvedMedia
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.EmptyState
import coil.compose.AsyncImage

@Composable
fun MediaViewerScreen(
    convId: String,
    messageId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val messages by AppServices.repository.messages.collectAsState()
    val message = messages[convId]?.firstOrNull { it.id == messageId }
    var resolved by remember { mutableStateOf<ResolvedMedia?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messageId) {
        var m = message
        if (m == null) {
            AppServices.repository.loadMessages(convId)
            m = AppServices.repository.messages.value[convId]?.firstOrNull { it.id == messageId }
        }
        if (m == null) {
            error = "Message not found"
            loading = false
            return@LaunchedEffect
        }
        val content = AppServices.repository.decryptMessage(m)
        if (content?.media == null) {
            error = "Not a media message"
            loading = false
            return@LaunchedEffect
        }
        try {
            resolved = AppServices.repository.resolveMedia(m, content, thumb = false)
        } catch (e: Exception) {
            error = e.message ?: "Could not load media"
        }
        loading = false
    }

    val isVideo = resolved?.mime?.startsWith("video") == true
    val player = remember(resolved) {
        if (isVideo) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(resolved!!.file)))
                prepare()
                playWhenReady = true
            }
        } else null
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    Box(Modifier.fillMaxSize().background(VoidBlack)) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp).align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = TechSilver)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TechSilver)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Outlined.ArrowBack, error!!)
            }
            isVideo && player != null -> AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxSize(),
            )
            resolved != null -> AsyncImage(
                model = resolved!!.file,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
