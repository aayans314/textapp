package app.textapp.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.textapp.AppServices
import app.textapp.data.ConversationDto
import app.textapp.ui.IndustrialGray
import app.textapp.ui.SteelShadow
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.Avatar
import app.textapp.ui.components.EmptyState
import app.textapp.ui.components.relativeTime

@Composable
fun ChatsScreen(onOpenChat: (String, String) -> Unit) {
    val conversations by AppServices.repository.conversations.collectAsState()
    val typing by AppServices.repository.typing.collectAsState()
    val presence by AppServices.repository.presence.collectAsState()

    LaunchedEffect(Unit) {
        AppServices.repository.refreshConversations()
    }

    Column(Modifier.fillMaxSize().background(VoidBlack)) {
        ScreenHeader("Chats")
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.Forum,
                    "No chats yet",
                    "Find your friends in People and start messaging.",
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(conversations, key = { it.id }) { conv ->
                    ChatRow(
                        conv = conv,
                        isTyping = typing[conv.id] != null,
                        online = presence[conv.peer.username] ?: false,
                        onClick = { onOpenChat(conv.id, conv.peer.username) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    conv: ConversationDto,
    isTyping: Boolean,
    online: Boolean,
    onClick: () -> Unit,
) {
    var preview by remember(conv.lastMsg?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(conv.lastMsg?.id) {
        preview = conv.lastMsg?.let { message ->
            AppServices.repository.decryptMessage(message)?.let { content ->
                when (content.t) {
                    "text" -> content.text
                    "media" -> if (content.media?.mime?.startsWith("video") == true) "Video" else "Photo"
                    else -> null
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conv.peer.username, online = online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conv.peer.username,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TechSilver,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    relativeTime(conv.lastMsg?.createdAt ?: conv.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = IndustrialGray,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    isTyping -> "typing…"
                    preview != null -> preview!!
                    else -> "New message"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isTyping) TechSilver else IndustrialGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conv.unread > 0) {
            Spacer(Modifier.width(10.dp))
            Badge(containerColor = SteelShadow) {
                Text("${conv.unread}", color = TechSilver)
            }
        }
    }
}
