package com.leaf.reader.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.ReaderHostFragment
import com.leaf.reader.domain.model.ReaderPreferences
import com.leaf.reader.domain.model.ReaderTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val activity = LocalContext.current.fragmentActivity()
    val model: ReaderViewModel = viewModel(viewModelStoreOwner = activity, key = "leaf-reader-$bookId", factory = activity.defaultViewModelProviderFactory)
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var bookmarkLabel by rememberSaveable { mutableStateOf("") }
    var addingBookmark by rememberSaveable { mutableStateOf(false) }
    val document = state.document
    val fragmentArguments = remember(bookId) { Bundle().apply { putString("bookId", bookId) } }
    LaunchedEffect(bookId) { model.initialize(bookId) }
    DisposableEffect(model, activity) { onDispose { if (!activity.isChangingConfigurations) model.closeReader() } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.message(null) } }
    BackHandler { if (panel != null) panel = null else { model.closeReader(); onBack() } }
    val dark = state.preferences.theme == ReaderTheme.DARK
    val page = when (state.preferences.theme) { ReaderTheme.LIGHT -> Color(0xFFFAFAF6); ReaderTheme.DARK -> Color(0xFF151A16); ReaderTheme.SEPIA -> Color(0xFFFAF3E3) }
    val ink = if (dark) Color(0xFFE7EADC) else Color(0xFF263229)
    DisposableEffect(activity, state.controlsVisible, dark) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val oldStatus = controller.isAppearanceLightStatusBars
        val oldNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.controlsVisible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = oldStatus
            controller.isAppearanceLightNavigationBars = oldNavigation
        }
    }
    Box(Modifier.fillMaxSize().background(page).testTag("reader-screen")) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Column(Modifier.align(Alignment.Center).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.error.orEmpty(), color = ink)
                Button(onClick = model::retry) { Text("Try again") }
                TextButton(onClick = { model.closeReader(); onBack() }) { Text("Back to library") }
            }
            document != null -> key(document) {
                AndroidFragment<ReaderHostFragment>(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility),
                    arguments = fragmentArguments,
                ) { host ->
                    host.onTap = model::toggleControls
                    host.onError = model::message
                    host.bind(document, state.preferences)
                }
            }
        }
        if (state.controlsVisible) {
            Surface(color = page.copy(alpha = 0.97f), contentColor = ink, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                Row(Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.closeReader(); onBack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Back to library") }
                    Column(Modifier.weight(1f)) {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        state.location?.title?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                    }
                    Box {
                        IconButton(onClick = { overflow = true }, enabled = document != null) { Icon(Icons.Default.MoreVert, "Reader options") }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(text = { Text("Contents") }, onClick = { overflow = false; panel = "contents" })
                            DropdownMenuItem(text = { Text("Add bookmark") }, enabled = state.location != null, onClick = { overflow = false; bookmarkLabel = state.location?.title.orEmpty(); addingBookmark = true })
                            DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { overflow = false; panel = "bookmarks" })
                            DropdownMenuItem(text = { Text("Appearance") }, onClick = { overflow = false; panel = "appearance" })
                            DropdownMenuItem(text = { Text("Hide controls") }, onClick = { overflow = false; model.toggleControls() })
                        }
                    }
                }
            }
            Surface(color = page.copy(alpha = 0.97f), contentColor = ink, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    var slider by remember(state.location?.progression) { mutableFloatStateOf(state.location?.progression?.toFloat() ?: 0f) }
                    Slider(value = slider, onValueChange = { slider = it }, onValueChangeFinished = { model.goProgression(slider.toDouble()) },
                        enabled = document != null, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Reading progress" })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = model::previous, enabled = document != null) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Previous page") }
                        val position = state.location?.position
                        val pageCount = document?.pageCount
                        Text(if (state.format == PublicationFormat.PDF && position != null && pageCount != null) "Page $position of $pageCount"
                            else "${((state.location?.progression ?: 0.0) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = model::next, enabled = document != null) { Icon(Icons.AutoMirrored.Default.ArrowForward, "Next page") }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (addingBookmark) AlertDialog(onDismissRequest = { addingBookmark = false }, title = { Text("Add bookmark") },
        text = { OutlinedTextField(value = bookmarkLabel, onValueChange = { bookmarkLabel = it.take(300) }, label = { Text("Label (optional)") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { model.addBookmark(bookmarkLabel); addingBookmark = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { addingBookmark = false }) { Text("Cancel") } })
    if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null }) {
        state.message?.let { message ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { model.message(null) }) { Text("Dismiss") }
            }
        }
        when (panel) {
            "contents" -> {
                Text("Contents", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                if (state.chapters.isEmpty()) Text("This book has no table of contents.", Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    items(state.chapters, key = { it.id }) { chapter ->
                        TextButton(onClick = { model.goChapter(chapter.id); panel = null }, modifier = Modifier.fillMaxWidth().padding(start = (chapter.depth.coerceAtMost(5) * 12).dp)) {
                            Text(chapter.title, Modifier.fillMaxWidth(), maxLines = 3)
                        }
                    }
                }
            }
            "bookmarks" -> {
                Text("Bookmarks", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                if (state.bookmarks.isEmpty()) Text("Save a place from the reader options menu.", Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    items(state.bookmarks, key = { it.id }) { bookmark ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { model.goBookmark(bookmark.locatorJson); panel = null }, modifier = Modifier.weight(1f)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(bookmark.label?.takeIf { it.isNotBlank() } ?: "Saved place")
                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(bookmark.createdAt)), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(onClick = { model.removeBookmark(bookmark.id) }) { Icon(Icons.Default.Delete, "Remove bookmark") }
                        }
                    }
                }
            }
            "appearance" -> ReaderAppearance(state.preferences, state.format, document?.supportsTypography == true, model::updatePreferences, model::resetPreferences)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReaderAppearance(preferences: ReaderPreferences, format: PublicationFormat, supportsTypography: Boolean, onChange: (ReaderPreferences) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reading appearance", style = MaterialTheme.typography.headlineSmall)
        Text("Changes apply to this book. Reset to use your defaults.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { theme -> FilterChip(selected = preferences.theme == theme, onClick = { onChange(preferences.copy(theme = theme)) }, label = { Text(theme.name.lowercase().replaceFirstChar(Char::titlecase)) }) }
        }
        if (supportsTypography) {
            Text("Font size · ${preferences.fontSize.roundToInt()}%")
            SettingSlider("Font size", preferences.fontSize, 50.0..300.0) { onChange(preferences.copy(fontSize = it)) }
            Text("Font family")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("serif", "sans-serif", "monospace").forEach { family -> FilterChip(selected = preferences.fontFamily == family, onClick = { onChange(preferences.copy(fontFamily = family)) }, label = { Text(when (family) { "sans-serif" -> "Sans"; "monospace" -> "Mono"; else -> "Serif" }) }) }
            }
            Text("Line spacing · ${"%.1f".format(preferences.lineSpacing)}")
            SettingSlider("Line spacing", preferences.lineSpacing, 0.8..3.0) { onChange(preferences.copy(lineSpacing = it)) }
            Text("Margins")
            SettingSlider("Margins", preferences.margins, 0.0..3.0) { onChange(preferences.copy(margins = it)) }
        } else if (format == PublicationFormat.PDF) {
            Text("PDF pages retain their original colors and typography. Theme changes affect reader controls.")
        } else {
            Text("This EPUB has a fixed layout. Its typography and pagination follow the publisher’s page design.")
        }
        if (supportsTypography || format == PublicationFormat.PDF) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Continuous scrolling", Modifier.weight(1f))
            Switch(checked = preferences.scroll, onCheckedChange = { onChange(preferences.copy(scroll = it)) }, modifier = Modifier.semantics { contentDescription = "Continuous scrolling" })
        }
        TextButton(onClick = onReset) { Text("Reset to reading defaults") }
    }
}

@Composable
private fun SettingSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft.toDouble()) }, valueRange = range.start.toFloat()..range.endInclusive.toFloat(), modifier = Modifier.semantics { contentDescription = label })
}

private tailrec fun Context.fragmentActivity(): FragmentActivity = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> error("Leaf's reader requires a FragmentActivity")
}
