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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.core.ui.ReaderInteractionHost
import com.leaf.reader.core.ui.ReadingActivityEligibilityEffect
import com.leaf.reader.data.readium.ReaderHostFragment
import com.leaf.reader.domain.model.ReaderPreferences
import com.leaf.reader.domain.model.ReaderTheme
import com.leaf.reader.domain.model.AnnotationLimits
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
    ReadingActivityEligibilityEffect(
        readerUsable = state.contentReady && document != null && !state.loading && state.error == null,
        modalOpen = panel != null || overflow || addingBookmark || state.selection != null || state.editingHighlight != null,
        onEligibilityChanged = model::foregroundEligible,
    )
    DisposableEffect(activity, model) {
        val host = activity as? ReaderInteractionHost
        val listener: () -> Unit = model::interact
        host?.onReadingInteraction = listener
        onDispose { host?.let { if (it.onReadingInteraction === listener) it.onReadingInteraction = null } }
    }
    val fragmentArguments = remember(bookId) { Bundle().apply { putString("bookId", bookId) } }
    fun dismissPanel() { if (panel == "search") model.cancelSearch(); panel = null }
    LaunchedEffect(panel) { if (panel != "search") model.cancelSearch() }
    LaunchedEffect(bookId) { model.initialize(bookId) }
    DisposableEffect(model, activity) { onDispose { if (!activity.isChangingConfigurations) model.closeReader() } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.message(null) } }
    LaunchedEffect(state.activityError) { state.activityError?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) } }
    BackHandler { if (panel != null) dismissPanel() else { model.closeReader(); onBack() } }
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
                TextButton(onClick = { model.closeReader(); onBack() }) { Text("Close reader") }
            }
            document != null -> key(document) {
                AndroidFragment<ReaderHostFragment>(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility),
                    arguments = fragmentArguments,
                ) { host ->
                    host.onTap = model::toggleControls
                    host.onInteraction = model::interact
                    host.onError = model::message
                    host.bind(document, state.preferences)
                }
            }
        }
        if (state.controlsVisible) {
            Surface(color = page.copy(alpha = 0.97f), contentColor = ink, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                Row(Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.closeReader(); onBack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Close reader") }
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
                            if (state.format == PublicationFormat.EPUB) {
                                DropdownMenuItem(text = { Text("Highlights and notes") }, onClick = { overflow = false; panel = "highlights" })
                                DropdownMenuItem(text = { Text("Search this book") }, onClick = { overflow = false; panel = "search" })
                            }
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
    if (addingBookmark) {
        val labelTooLarge = bookmarkLabel.length > AnnotationLimits.MAX_LABEL_BYTES ||
            bookmarkLabel.toByteArray(Charsets.UTF_8).size > AnnotationLimits.MAX_LABEL_BYTES
        AlertDialog(onDismissRequest = { addingBookmark = false }, title = { Text("Add bookmark") },
            text = { OutlinedTextField(value = bookmarkLabel, onValueChange = { bookmarkLabel = it },
                label = { Text("Label (optional)") }, singleLine = true, isError = labelTooLarge,
                supportingText = { Text(if (labelTooLarge) "Your label is too long. Shorten it before saving." else "An optional name for this saved place.") }) },
            confirmButton = { TextButton(onClick = { model.addBookmark(bookmarkLabel); addingBookmark = false }, enabled = !labelTooLarge) { Text("Save") } },
            dismissButton = { TextButton(onClick = { addingBookmark = false }) { Text("Cancel") } })
    }
    state.selection?.let { selection ->
        key(selection.locatorJson) {
            AnnotationEditor(title = "Highlight passage", selectedText = selection.selectedText, initialColor = 0xFFFFD976L,
                initialNote = "", saving = state.annotationSaving, error = state.annotationError,
                onDismiss = model::dismissSelection, onSave = model::saveSelection)
        }
    }
    state.highlights.find { it.id == state.editingHighlight }?.let { highlight ->
        key(highlight.id) { AnnotationEditor(title = "Highlight and note", selectedText = highlight.selectedText,
            initialColor = highlight.color, initialNote = highlight.note.orEmpty(), saving = state.annotationSaving,
            error = state.annotationError, onDismiss = model::closeHighlightEditor,
            onSave = { color, note -> model.updateHighlight(highlight.id, color, note) }, onDelete = { model.deleteHighlight(highlight.id) }) }
    }
    if (panel != null) ModalBottomSheet(onDismissRequest = ::dismissPanel) {
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
            "highlights" -> {
                Text("Highlights and notes", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                if (state.highlightsLoading) CircularProgressIndicator(Modifier.padding(horizontal = 24.dp))
                state.highlightsError?.let { error ->
                    Text(error, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = model::retryHighlights, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Retry loading annotations") }
                }
                if (!state.highlightsLoading && state.highlightsError == null && state.highlights.isEmpty()) Text(if (document?.supportsTextSelection == true)
                    "Select text in the book, then choose Highlight." else "Text highlighting is available in reflowable EPUB books.", Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    items(state.highlights, key = { it.id }) { highlight ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(highlight.selectedText, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (!highlight.note.isNullOrBlank()) Text(highlight.note, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(highlight.createdAt)), style = MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick = { model.goBookmark(highlight.locatorJson); panel = null }) { Text("Go to passage") }
                                TextButton(onClick = { model.editHighlight(highlight.id); panel = null }) { Text("Edit note") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            "search" -> ReaderSearchPanel(state, model) { result -> model.goSearchResult(result); panel = null }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AnnotationEditor(title: String, selectedText: String, initialColor: Long, initialNote: String, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (Long, String) -> Unit, onDelete: (() -> Unit)? = null) {
    var color by rememberSaveable { mutableLongStateOf(initialColor) }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var rejectedInput by rememberSaveable { mutableStateOf(false) }
    val noteTooLarge = rejectedInput || !AnnotationLimits.fitsNote(note)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(selectedText, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Text("Highlight color", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                listOf("Gold" to 0xFFFFD976L, "Green" to 0xFF9CCC9BL, "Blue" to 0xFF94C9E8L, "Pink" to 0xFFEBA9C2L).forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = color == value,
                        enabled = !saving, role = Role.RadioButton, onClick = { color = value }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = color == value, onClick = null, enabled = !saving)
                        Text(label)
                    }
                }
            }
            OutlinedTextField(value = note, onValueChange = { value ->
                rejectedInput = value.length > AnnotationLimits.MAX_NOTE_BYTES
                if (!rejectedInput) note = value
            }, label = { Text("Personal note (optional)") }, isError = noteTooLarge, enabled = !saving,
                supportingText = { Text(if (noteTooLarge) "Your note is too long. Shorten it before saving." else "Add a personal note for this passage.") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("annotation-note"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (onDelete != null) TextButton(onClick = onDelete, enabled = !saving, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete highlight and note") }
        } },
        confirmButton = { TextButton(onClick = { onSave(color, note) }, enabled = !noteTooLarge && !saving, modifier = Modifier.testTag("annotation-save")) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}

@Composable
private fun ReaderSearchPanel(state: ReaderUiState, model: ReaderViewModel, onResult: (com.leaf.reader.data.readium.ReaderSearchResult) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submitSearch = { keyboard?.hide(); model.search() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Search this book", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = state.searchQuery, onValueChange = model::changeSearchQuery, label = { Text("Words or phrase") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
            isError = state.searchQueryRejected, supportingText = { Text("Up to 200 characters") }, modifier = Modifier.fillMaxWidth().testTag("reader-search-query"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = submitSearch, enabled = state.searchQuery.isNotBlank() && !state.searchQueryRejected && !state.searchRunning) { Text("Search") }
            if (state.searchRunning) { Spacer(Modifier.width(16.dp)); CircularProgressIndicator(Modifier.size(24.dp)); TextButton(onClick = model::cancelSearch) { Text("Stop") } }
        }
        state.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.searchStopped) Text("Search stopped. Run it again to search the whole book.")
        if (state.searchStarted && !state.searchRunning && !state.searchStopped && state.searchResults.isEmpty() && state.searchError == null) Text("No matching passages. Try another phrase.")
        if (state.searchResults.isNotEmpty()) Text("${state.searchResults.size} matching passages${if (state.searchRunning || state.searchStopped || state.searchError != null) " so far" else ""}", style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("reader-search-results")) {
            items(state.searchResults, key = { it.id }) { result ->
                TextButton(onClick = { onResult(result) }, modifier = Modifier.fillMaxWidth().testTag("reader-search-result")) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        result.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(buildAnnotatedString {
                            append(result.before)
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(result.match) }
                            append(result.after)
                        }, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
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
