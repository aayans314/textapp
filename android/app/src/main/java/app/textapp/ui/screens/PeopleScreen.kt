package app.textapp.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.textapp.AppServices
import app.textapp.data.ApiException
import app.textapp.data.FriendItem
import app.textapp.data.FriendRequestItem
import app.textapp.data.UserDto
import app.textapp.ui.IndustrialGray
import app.textapp.ui.SteelShadow
import app.textapp.ui.TechSilver
import app.textapp.ui.VoidBlack
import app.textapp.ui.components.AppTextField
import app.textapp.ui.components.Avatar
import app.textapp.ui.components.EmptyState
import app.textapp.ui.components.SectionHeader
import app.textapp.ui.components.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PeopleScreen(onOpenChatWithUser: (String) -> Unit) {
    val friends by AppServices.repository.friends.collectAsState()
    val requests by AppServices.repository.requests.collectAsState()
    val presence by AppServices.repository.presence.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        AppServices.repository.refreshFriends()
    }

    LaunchedEffect(query) {
        searching = true
        if (query.isBlank()) {
            results = emptyList()
        } else {
            delay(300) // debounce rapid typing
            results = runCatching { AppServices.repository.searchUsers(query) }.getOrDefault(emptyList())
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().background(VoidBlack)) {
        ScreenHeader("People")
        Box(Modifier.padding(horizontal = 16.dp)) {
            AppTextField(
                value = query,
                onValueChange = { query = it },
                label = "Find by username",
                leadingIcon = Icons.Outlined.Search,
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (requests.isNotEmpty()) {
                item { SectionHeader("Requests") }
                items(requests, key = { "req_${it.user.id}" }) { req ->
                    RequestRow(req) { accept ->
                        scope.launch {
                            try {
                                AppServices.repository.respondFriend(req.user.username, accept)
                            } catch (e: ApiException) {
                                toast(context, e.message ?: "failed")
                            }
                        }
                    }
                }
                item { HorizontalDivider(color = SteelShadow, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (results.isNotEmpty()) {
                item { SectionHeader("Results") }
                items(results, key = { "res_${it.id}" }) { user ->
                    val isFriend = friends.any { it.user.id == user.id }
                    SearchRow(user, isFriend) {
                        scope.launch {
                            try {
                                AppServices.repository.requestFriend(user.username)
                                toast(context, "Request sent to ${user.username}")
                            } catch (e: ApiException) {
                                toast(context, e.message ?: "failed")
                            }
                        }
                    }
                }
                item { HorizontalDivider(color = SteelShadow, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (friends.isEmpty() && results.isEmpty() && requests.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp)) {
                        EmptyState(
                            Icons.Outlined.PersonAdd,
                            "No friends yet",
                            "Search a username to send a friend request.",
                        )
                    }
                }
            } else {
                item { SectionHeader("Friends") }
                items(friends, key = { "f_${it.user.id}" }) { friend ->
                    FriendRow(friend, online = presence[friend.user.username] ?: friend.online) {
                        onOpenChatWithUser(friend.user.username)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(item: FriendRequestItem, onRespond: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.user.username)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.user.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TechSilver)
            Text("wants to chat", style = MaterialTheme.typography.bodySmall, color = IndustrialGray)
        }
        OutlinedButton(onClick = { onRespond(true) }, shape = RoundedCornerShape(12.dp)) {
            Text("Accept", color = TechSilver)
        }
        TextButton(onClick = { onRespond(false) }) {
            Text("Decline", color = IndustrialGray)
        }
    }
}

@Composable
private fun SearchRow(user: UserDto, isFriend: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.username)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TechSilver)
            Text(if (isFriend) "Already friends" else "Not in your contacts yet", style = MaterialTheme.typography.bodySmall, color = IndustrialGray)
        }
        if (!isFriend) {
            OutlinedButton(onClick = onAdd, shape = RoundedCornerShape(12.dp)) {
                Text("Add", color = TechSilver)
            }
        }
    }
}

@Composable
private fun FriendRow(friend: FriendItem, online: Boolean, onMessage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(friend.user.username, online = online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(friend.user.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TechSilver)
            Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.bodySmall, color = IndustrialGray)
        }
        OutlinedButton(onClick = onMessage, shape = RoundedCornerShape(12.dp)) {
            Text("Message", color = TechSilver)
        }
    }
}
