package app.textapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import app.textapp.AppServices
import app.textapp.data.ApiException
import app.textapp.data.DecryptResult
import app.textapp.data.MediaPayloadDto
import app.textapp.data.MessageDto
import app.textapp.data.PayloadDto
import app.textapp.data.ResolvedMedia
import app.textapp.data.SessionState
import app.textapp.ui.CarbonCharcoal
import app.textapp.ui.IndustrialGray
import app.textapp.ui.SteelShadow
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.Avatar
import app.textapp.ui.components.AppTextField
import app.textapp.ui.components.EmptyState
import app.textapp.ui.components.relativeTime
import app.textapp.ui.components.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

data class SendState(val stage: String, val progress: Float)

private fun captureTarget(context: android.content.Context, ext: String): Uri {
    val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.$ext")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun ChatScreen(
    convId: String,
    peerName: String,
    onBack: () -> Unit,
    onOpenMedia: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by AppServices.repository.messages.collectAsState()
    val conversations by AppServices.repository.conversations.collectAsState()
    val presence by AppServices.repository.presence.collectAsState()
    val typing by AppServices.repository.typing.collectAsState()
    val sessionState by AppServices.session.state.collectAsState(initial = SessionState())

    val conv = conversations.firstOrNull { it.id == convId }
    val list = messages[convId] ?: emptyList()
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf<SendState?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(convId) {
        AppServices.repository.setActiveConversation(convId)
        AppServices.repository.loadMessages(convId)
        AppServices.repository.markRead(convId)
    }
    DisposableEffect(Unit) {
        onDispose { AppServices.repository.setActiveConversation(null) }
    }
    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.lastIndex)
    }

    fun sendUri(uri: Uri, isVideo: Boolean) {
        val c = conv ?: return
        scope.launch {
            sending = SendState("Preparing…", 0f)
            try {
                AppServices.repository.sendMedia(
                    c, uri, isVideo,
                    onStage = { stage -> sending = SendState(stage, sending?.progress ?: 0f) },
                    onProgress = { p -> sending = SendState(sending?.stage ?: "Uploading…", p) },
                )
            } catch (e: Exception) {
                toast(context, e.message ?: "send failed")
            } finally {
                sending = null
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { sendUri(it, isVideo = false) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { sendUri(it, isVideo = true) }
    }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraTarget?.let { if (ok) sendUri(it, isVideo = false) }
        cameraTarget = null
    }
    val videoCaptureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        cameraTarget?.let { if (ok) sendUri(it, isVideo = true) }
        cameraTarget = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraTarget = captureTarget(context, "jpg")
            cameraLauncher.launch(cameraTarget!!)
        }
    }

    fun sendText() {
        val text = draft.trim()
        if (text.isEmpty() || conv == null) return
        scope.launch {
            try {
                AppServices.repository.sendText(conv, text)
                draft = ""
            } catch (e: Exception) {
                toast(context, e.message ?: "send failed")
            }
        }
    }

    LaunchedEffect(draft) {
        if (draft.isNotBlank()) {
            AppServices.repository.sendTyping(convId)
            delay(1500)
        }
    }

    Column(Modifier.fillMaxSize().background(VoidBlack).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(CarbonCharcoal).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = TechSilver)
            }
            Avatar(peerName, size = 40.dp, online = presence[peerName] ?: false)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TechSilver)
                val typingNow = typing[convId]
                Text(
                    if (typingNow != null) "typing…" else if (presence[peerName] == true) "Online" else "Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (typingNow != null) TechSilver else IndustrialGray,
                )
            }
        }

        Box(Modifier.weight(1f)) {
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.Forum, "Say hello", "Messages are end-to-end encrypted.")
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list, key = { it.id }) { message ->
                        MessageRow(
                            message,
                            isMine = message.senderId == sessionState.userId,
                            onOpenMedia = { onOpenMedia(message.id) },
                        )
                    }
                }
            }
        }

        sending?.let { SendOverlay(it) }

        Row(
            Modifier.fillMaxWidth().background(CarbonCharcoal).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = { showAttach = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Attach", tint = TechSilver)
                }
                DropdownMenu(expanded = showAttach, onDismissRequest = { showAttach = false }) {
                    DropdownMenuItem(
                        text = { Text("Photo from library") },
                        leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                        onClick = {
                            showAttach = false
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Video from library") },
                        leadingIcon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                        onClick = {
                            showAttach = false
                            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Take photo") },
                        leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                        onClick = {
                            showAttach = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraTarget = captureTarget(context, "jpg")
                                cameraLauncher.launch(cameraTarget!!)
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Record video") },
                        leadingIcon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                        onClick = {
                            showAttach = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraTarget = captureTarget(context, "mp4")
                                videoCaptureLauncher.launch(cameraTarget!!)
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                }
            }
            AppTextField(
                value = draft,
                onValueChange = { draft = it },
                label = "Message",
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ::sendText, enabled = draft.isNotBlank()) {
                Icon(
                    Icons.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (draft.isNotBlank()) TechSilver else IndustrialGray,
                )
            }
        }
    }
}

@Composable
private fun SendOverlay(state: SendState) {
    Column(Modifier.fillMaxWidth().background(CarbonCharcoal).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(state.stage, style = MaterialTheme.typography.labelMedium, color = IndustrialGray)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (state.progress > 0f) state.progress else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = TechSilver,
            trackColor = SteelShadow,
        )
    }
}

@Composable
private fun MessageRow(
    m: MessageDto,
    isMine: Boolean,
    onOpenMedia: () -> Unit,
) {
    var decrypt by remember(m.id) { mutableStateOf<DecryptResult?>(null) }
    var thumb by remember(m.id) { mutableStateOf<ResolvedMedia?>(null) }
    LaunchedEffect(m.id) {
        decrypt = AppServices.repository.decryptMessageReason(m)
        if (decrypt?.payload?.media != null) {
            thumb = runCatching { AppServices.repository.resolveMedia(m, decrypt!!.payload!!, thumb = true) }.getOrNull()
        }
    }
    val bubbleColor = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(Modifier.widthIn(max = 300.dp)) {
            Box(
                Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomEnd = if (isMine) 4.dp else 18.dp,
                            bottomStart = if (isMine) 18.dp else 4.dp,
                        ),
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                when {
                    decrypt?.payload == null -> Text(
                        decrypt?.reason?.let { "Can't decrypt: $it" } ?: "Can't decrypt",
                        color = textColor.copy(alpha = 0.5f),
                    )
                    decrypt?.payload?.t == "text" -> Text(
                        decrypt?.payload?.text ?: "",
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    decrypt?.payload?.t == "media" && decrypt?.payload?.media != null -> MediaContent(
                        decrypt!!.payload!!.media!!,
                        thumb,
                        onOpenMedia,
                    )
                }
            }
            Row(Modifier.align(if (isMine) Alignment.End else Alignment.Start).padding(top = 2.dp, end = 4.dp)) {
                Text(relativeTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = IndustrialGray)
                if (isMine && m.readAt != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.DoneAll, contentDescription = "Read", tint = TechSilver, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun MediaContent(
    media: MediaPayloadDto,
    thumb: ResolvedMedia?,
    onOpenMedia: () -> Unit,
) {
    Box(
        Modifier
            .width(220.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenMedia),
    ) {
        if (thumb != null) {
            AsyncImage(
                model = thumb.file,
                contentDescription = media.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (media.mime.startsWith("video")) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = TechSilver, modifier = Modifier.size(48.dp))
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(SteelShadow), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = IndustrialGray, strokeWidth = 2.dp)
            }
        }
    }
}
