package com.leaf.reader.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.leaf.reader.feature.bookdetails.BookDetailsScreen
import com.leaf.reader.feature.library.LibraryScreen
import com.leaf.reader.feature.library.LibraryViewModel
import com.leaf.reader.feature.reader.ReaderScreen

private val destinations = listOf("Library", "Shelves", "Activity", "Settings")

@Composable
fun LeafApp(model: LibraryViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "Library"
    val reader = route.startsWith("reader/")
    val message by model.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.takeIf(String::isNotBlank)?.let {
            snackbar.showSnackbar(it, actionLabel = "Dismiss", duration = SnackbarDuration.Long)
            model.dismissMessage()
        }
    }
    Scaffold(contentWindowInsets = if (reader) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (!reader) NavigationBar {
            destinations.forEachIndexed { index, destination ->
                NavigationBarItem(selected = route == destination || (destination == "Library" && route.startsWith("book/")),
                    onClick = { nav.navigate(destination) { launchSingleTop = true; restoreState = true; popUpTo("Library") { saveState = true } } },
                    modifier = Modifier.testTag("nav-$destination"),
                    icon = { Icon(listOf(Icons.Default.Home, Icons.Default.Favorite, Icons.Default.DateRange, Icons.Default.Settings)[index], contentDescription = null) },
                    label = { Text(destination) })
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = "Library", modifier = Modifier.padding(padding)) {
            composable("Library") {
                val state by model.state.collectAsStateWithLifecycle()
                LibraryScreen(state, model, onBook = { nav.navigate("book/$it") }, onRead = { nav.navigate("reader/$it") })
            }
            composable("book/{bookId}") { destination ->
                val id = destination.arguments?.getString("bookId").orEmpty()
                val state by model.state.collectAsStateWithLifecycle()
                BookDetailsScreen(state.books.find { it.id == id }, state.loading,
                    onBack = { nav.popBackStack() }, onStatus = { model.setStatus(id, it) },
                    onRemove = { model.remove(id); nav.popBackStack() }, onOpen = { nav.navigate("reader/$id") })
            }
            composable("reader/{bookId}") { destination ->
                ReaderScreen(destination.arguments?.getString("bookId").orEmpty(), onBack = { nav.popBackStack() })
            }
            destinations.drop(1).forEach { destination ->
                composable(destination) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(destination, style = MaterialTheme.typography.headlineLarge)
                        Text(when (destination) {
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
