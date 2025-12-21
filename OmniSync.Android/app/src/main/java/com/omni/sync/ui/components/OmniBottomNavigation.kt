package com.omni.sync.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.omni.sync.viewmodel.AppScreen

import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import kotlin.math.abs

// Create a simple data class to hold nav info
data class NavItem(
    val screen: AppScreen,
    val title: String,
    val icon: ImageVector
)

// Define the main navigation tabs (without Process)
val navigationItems = listOf(
    NavItem(AppScreen.DASHBOARD, "Dash", Icons.Default.Dashboard),
    NavItem(AppScreen.REMOTECONTROL, "Remote", Icons.Default.Gamepad),
    NavItem(AppScreen.BROWSER, "Browser", Icons.Default.Public),
    NavItem(AppScreen.FILES, "Files", Icons.Default.Folder),
    NavItem(AppScreen.AI_CHAT, "AI", Icons.Default.SmartToy)
)

// Define the burger menu items
val burgerMenuItems = listOf(
    NavItem(AppScreen.PROCESS, "Process", Icons.Default.Memory),
    NavItem(AppScreen.ALARM, "Alarm", Icons.Default.Alarm)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OmniBottomNavigation(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onSwipe: (Int) -> Unit = {}
) {
    var showBurgerMenu by remember { mutableStateOf(false) }
    var totalDrag by remember { mutableStateOf(0f) }
    
    // We only want to show the Bottom Bar if we are on one of the main screens.    
    // If we are in the Video Player, we usually want to hide it (optional).        
    // Also hide when keyboard is up to save space.
    val isKeyboardVisible = WindowInsets.isImeVisible
    val allVisibleScreens = navigationItems.map { it.screen } + burgerMenuItems.map { it.screen }
    val showBottomBar = allVisibleScreens.any { it == currentScreen } && !isKeyboardVisible

    if (showBottomBar) {
        NavigationBar(
            modifier = Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(totalDrag) > 100) {
                            if (totalDrag > 0) onSwipe(-1)
                            else onSwipe(1)
                        }
                        totalDrag = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    }
                )
            }
        ) {
            // Main navigation items
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
                    alwaysShowLabel = false
                )
            }
            
            // Burger menu button
            NavigationBarItem(
                selected = burgerMenuItems.any { it.screen == currentScreen },
                onClick = { showBurgerMenu = true },
                icon = { 
                    Icon(
                        imageVector = Icons.Default.Menu, 
                        contentDescription = "More" 
                    )
                    
                    // Dropdown menu
                    DropdownMenu(
                        expanded = showBurgerMenu,
                        onDismissRequest = { showBurgerMenu = false }
                    ) {
                        burgerMenuItems.forEach { item ->
                            DropdownMenuItem(
                                text = { 
                                    Text(text = item.title) 
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title
                                    )
                                },
                                onClick = {
                                    showBurgerMenu = false
                                    onNavigate(item.screen)
                                }
                            )
                        }
                    }
                },
                label = { Text(text = "More") },
                alwaysShowLabel = false
            )
        }
    }
}
