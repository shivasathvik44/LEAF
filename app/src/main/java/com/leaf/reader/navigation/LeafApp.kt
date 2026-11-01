package com.leaf.reader.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*

private val destinations = listOf("Library", "Shelves", "Activity", "Settings")

@Composable
fun LeafApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "Library"
    Scaffold(bottomBar = {
        NavigationBar {
            destinations.forEachIndexed { index, destination ->
                NavigationBarItem(selected = route == destination,
                    onClick = { nav.navigate(destination) { launchSingleTop = true; restoreState = true; popUpTo("Library") { saveState = true } } },
                    icon = { Icon(listOf(Icons.Default.Home, Icons.Default.Favorite, Icons.Default.DateRange, Icons.Default.Settings)[index], contentDescription = null) },
                    label = { Text(destination) })
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = "Library", modifier = Modifier.padding(padding)) {
            destinations.forEach { destination ->
                composable(destination) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (destination == "Library") "Leaf" else destination, style = MaterialTheme.typography.headlineLarge)
                        Text(when (destination) {
                            "Library" -> "Your reading, at your pace."
                            "Shelves" -> "Make room for your next good read."
                            "Activity" -> "Every quiet minute counts."
                            else -> "Private by design. Your books stay on this device."
                        }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
