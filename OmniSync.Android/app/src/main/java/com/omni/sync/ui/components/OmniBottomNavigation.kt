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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.omni.sync.viewmodel.AppScreen

import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AccountTree
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

// Canonical list: the original first five remain visible for existing installations.
val defaultNavigationItems = listOf(
    NavItem(AppScreen.DASHBOARD, "Dash", Icons.Default.Dashboard),
    NavItem(AppScreen.REMOTECONTROL, "Remote", Icons.Default.Gamepad),
    NavItem(AppScreen.BROWSER, "Browser", Icons.Default.Public),
    NavItem(AppScreen.FILES, "Files", Icons.Default.Folder),
    NavItem(AppScreen.AGENTS, "AI", Icons.Default.SmartToy),
    NavItem(AppScreen.PROCESS, "Process", Icons.Default.Memory),
    NavItem(AppScreen.ALARM, "Alarm", Icons.Default.Alarm),
    NavItem(AppScreen.WEB_SERVER, "OmniWeb", Icons.Default.Public),
    NavItem(AppScreen.TASKBOARD, "Tasks", Icons.Default.Assignment),
    NavItem(AppScreen.OMNIDESIGN, "Design", Icons.Default.AccountTree),
    NavItem(AppScreen.TESTS, "Prototypes", Icons.Default.Assignment),
    NavItem(AppScreen.BOOKS, "Books", Icons.Default.MenuBook)
)

// Ignore retired/duplicate names and append newly added screens in their default order.
fun configuredNavigationItems(savedOrder: List<String>?): List<NavItem> {
    val byId = defaultNavigationItems.associateBy { it.screen.name }
    val ordered = savedOrder.orEmpty().distinct().mapNotNull(byId::get)
    return ordered + defaultNavigationItems.filterNot { it in ordered }
}

fun configuredVisibleCount(savedCount: Int): Int =
    (if (savedCount <= 0) 5 else savedCount).coerceIn(1, defaultNavigationItems.size)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OmniBottomNavigation(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    swipeNavigationEnabled: Boolean = false,
    onSwipe: (Int) -> Unit = {},
    navigationOrder: List<String>? = null,
    navigationVisibleCount: Int = 5
) {
    var showBurgerMenu by remember { mutableStateOf(false) }
    var totalDrag by remember { mutableStateOf(0f) }
    
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val orderedItems = configuredNavigationItems(navigationOrder)
    val visibleItems = orderedItems.take(configuredVisibleCount(navigationVisibleCount))
    val hiddenItems = orderedItems.drop(visibleItems.size)
    val activeNavigationScreen = when (currentScreen) {
        AppScreen.EDITOR -> AppScreen.FILES
        AppScreen.AGENT_DETAIL, AppScreen.AGENT_TERMINAL -> AppScreen.AGENTS
        else -> currentScreen
    }
    val showBottomBar = orderedItems.any { it.screen == activeNavigationScreen } && !isLandscape

    // Match the AI workspace's neutral T3-like surfaces even when disconnected;
    // the global offline palette otherwise renders a burgundy navigation bar.
    val aiWorkspace = currentScreen == AppScreen.AGENTS || currentScreen == AppScreen.AGENT_DETAIL ||
        currentScreen == AppScreen.AGENT_TERMINAL
    val aiDark = isSystemInDarkTheme()
    val aiSurface = if (aiDark) Color(0xFF191E26) else Color.White
    val aiText = if (aiDark) Color(0xFFF1F3F6) else Color(0xFF1B2533)
    val aiMuted = if (aiDark) Color(0xFF9DA8B8) else Color(0xFF697789)
    val aiSelected = if (aiDark) Color(0xFF334862) else Color(0xFFDDEAFF)
    val aiAccent = if (aiDark) Color(0xFFAFCEFF) else Color(0xFF3262A4)
    val aiBarColors = NavigationBarItemDefaults.colors(
        selectedIconColor = aiAccent, selectedTextColor = aiAccent,
        indicatorColor = aiSelected, unselectedIconColor = aiMuted,
        unselectedTextColor = aiMuted)

    if (showBottomBar) {
        NavigationBar(
            containerColor = if (aiWorkspace) aiSurface else NavigationBarDefaults.containerColor,
            contentColor = if (aiWorkspace) aiText else contentColorFor(NavigationBarDefaults.containerColor),
            modifier = if (swipeNavigationEnabled) Modifier.pointerInput(swipeNavigationEnabled) {
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
            } else Modifier
        ) {
            // Main navigation items
            visibleItems.forEach { item ->
                val isSelected = activeNavigationScreen == item.screen
                
                NavigationBarItem(
                    colors = if (aiWorkspace) aiBarColors else NavigationBarItemDefaults.colors(),
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
            
            // Only show More while there are screens outside the visible set.
            if (hiddenItems.isNotEmpty()) NavigationBarItem(
                colors = if (aiWorkspace) aiBarColors else NavigationBarItemDefaults.colors(),
                selected = hiddenItems.any { it.screen == activeNavigationScreen },
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
                        hiddenItems.forEach { item ->
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

