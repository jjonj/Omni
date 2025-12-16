package com.omni.sync.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.omni.sync.viewmodel.AppScreen

// Create a simple data class to hold nav info
data class NavItem(
    val screen: AppScreen,
    val title: String,
    val icon: ImageVector
)

// Define the list of tabs
val navigationItems = listOf(
    NavItem(AppScreen.DASHBOARD, "Dashboard", Icons.Default.Dashboard),
    NavItem(AppScreen.REMOTECONTROL, "Remote", Icons.Default.Gamepad),
    NavItem(AppScreen.NOTEVIEWER, "Notes", Icons.Default.Description),
    NavItem(AppScreen.PROCESS, "Process", Icons.Default.Memory),
    NavItem(AppScreen.FILES, "Files", Icons.Default.Folder)
)

@Composable
fun OmniBottomNavigation(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit
) {
    // We only want to show the Bottom Bar if we are on one of the main screens.
    // If we are in the Video Player, we usually want to hide it (optional).
    val showBottomBar = navigationItems.any { it.screen == currentScreen }

    if (showBottomBar) {
        NavigationBar {
            navigationItems.forEach { item ->
                val isSelected = currentScreen == item.screen
                
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onNavigate(item.screen) },
                    icon = { 
                        Icon(
                            imageVector = item.icon, 
                            contentDescription = item.title 
                        ) 
                    },
                    label = { Text(text = item.title) },
                    // specialized configuration for professional look:
                    alwaysShowLabel = false // Only show label when selected (cleaner for 5 items)
                )
            }
        }
    }
}
