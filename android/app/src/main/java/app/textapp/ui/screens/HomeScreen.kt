package app.textapp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.textapp.ui.CarbonCharcoal
import app.textapp.ui.IndustrialGray
import app.textapp.ui.SteelShadow
import app.textapp.ui.TechSilver

@Composable
fun HomeScreen(
    onOpenChat: (String, String) -> Unit,
    onOpenChatWithUser: (String) -> Unit,
    onLoggedOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = app.textapp.ui.VoidBlack,
        bottomBar = {
            NavigationBar(containerColor = CarbonCharcoal) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                    label = { Text("Chats") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TechSilver,
                        selectedTextColor = TechSilver,
                        indicatorColor = SteelShadow,
                        unselectedIconColor = IndustrialGray,
                        unselectedTextColor = IndustrialGray,
                    ),
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Group, contentDescription = null) },
                    label = { Text("People") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TechSilver,
                        selectedTextColor = TechSilver,
                        indicatorColor = SteelShadow,
                        unselectedIconColor = IndustrialGray,
                        unselectedTextColor = IndustrialGray,
                    ),
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TechSilver,
                        selectedTextColor = TechSilver,
                        indicatorColor = SteelShadow,
                        unselectedIconColor = IndustrialGray,
                        unselectedTextColor = IndustrialGray,
                    ),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatsScreen(onOpenChat)
                1 -> PeopleScreen(onOpenChatWithUser)
                else -> SettingsScreen(onLoggedOut)
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String) {
    Text(
        title,
        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        color = TechSilver,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
