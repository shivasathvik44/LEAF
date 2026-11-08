package com.leaf.reader.feature.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.reader.BuildConfig
import com.leaf.reader.core.ui.PreferenceSlider
import com.leaf.reader.core.ui.leafActivityViewModel
import com.leaf.reader.data.local.files.RecoveredBookCopy
import com.leaf.reader.domain.model.AppTheme
import com.leaf.reader.domain.model.BookSort
import com.leaf.reader.domain.model.LeafPreferences
import com.leaf.reader.domain.model.ReaderTheme
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val accents = listOf(0xFF91B78FL to "Fern", 0xFF94C9E8L to "Sky", 0xFFE4BB85L to "Amber", 0xFFC5AFE7L to "Lavender")
private val fonts = listOf("serif" to "Serif", "sans-serif" to "Sans serif", "monospace" to "Monospace")
private val sorts = listOf(BookSort.IMPORTED to "Recently imported", BookSort.LAST_OPENED to "Last opened",
    BookSort.TITLE to "Title", BookSort.AUTHOR to "Author", BookSort.PROGRESS to "Reading progress")

@Composable
internal fun SettingsScreen(preferences: LeafPreferences, update: ((LeafPreferences) -> LeafPreferences) -> Unit) {
    val model = leafActivityViewModel<StorageSettingsViewModel>("leaf-storage-settings")
    val storage by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val locale = LocalLocale.current.platformLocale
    val reader = preferences.reader
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    var recoveryOpen by rememberSaveable { mutableStateOf(false) }
    var linkUnavailable by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model) { model.refresh() }
    if (licensesOpen) {
        LicenseNotices { licensesOpen = false }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings-screen")) {
        SettingsHeading("Settings", primary = true)
        SettingsHeading("Appearance")
        PreferenceChoice("App theme", preferences.appTheme, AppTheme.entries.map { it to it.label() }) { theme ->
            update { it.copy(appTheme = theme) }
        }
        PreferenceChoice("Accent color", preferences.accent, accents) { accent -> update { it.copy(accent = accent) } }
        PreferenceChoice("Default reader theme", reader.theme, ReaderTheme.entries.map { it to it.label() }) { theme ->
            update { it.copy(reader = it.reader.copy(theme = theme)) }
        }
        HorizontalDivider()
        SettingsHeading("Reader defaults")
        Text("Books with custom appearance keep their own settings.", Modifier.padding(horizontal = 16.dp))
        PreferenceChoice("Font family", reader.fontFamily, fonts) { family -> update { it.copy(reader = it.reader.copy(fontFamily = family)) } }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Font size · ${reader.fontSize.roundToInt()}%")
            PreferenceSlider("Default font size", reader.fontSize, 50.0..300.0) { size -> update { it.copy(reader = it.reader.copy(fontSize = size)) } }
            Text("Line spacing · ${String.format(locale, "%.1f", reader.lineSpacing)}")
            PreferenceSlider("Default line spacing", reader.lineSpacing, 0.8..3.0) { spacing -> update { it.copy(reader = it.reader.copy(lineSpacing = spacing)) } }
            Text("Margins · ${String.format(locale, "%.1f", reader.margins)}")
            PreferenceSlider("Default margins", reader.margins, 0.0..3.0) { margins -> update { it.copy(reader = it.reader.copy(margins = margins)) } }
        }
        PreferenceToggle("Continuous scrolling", reader.scroll) { scroll -> update { it.copy(reader = it.reader.copy(scroll = scroll)) } }
        HorizontalDivider()
        SettingsHeading("Library")
        PreferenceChoice("Default sort", preferences.sort, sorts) { sort -> update { it.copy(sort = sort) } }
        PreferenceToggle("List view", preferences.listView) { listView -> update { it.copy(listView = listView) } }
        HorizontalDivider()
        SettingsHeading("Storage")
        ListItem(headlineContent = { Text("Imported storage usage") }, supportingContent = {
            Text(storage.bytes?.let { "${Formatter.formatFileSize(context, it)} in private books, covers, and recovery copies" }
                ?: if (storage.busy) "Checking private storage…" else "Storage usage is unavailable.")
        })
        TextButton(onClick = model::clearTemporaryFiles, enabled = !storage.busy) { Text("Clear temporary import files") }
        Text("Cleanup removes abandoned import files. Books and recovery copies stay in private storage.", Modifier.padding(horizontal = 16.dp))
        if (storage.recovered.isNotEmpty()) SettingsAction("Recovered books", "${storage.recovered.size} private copies available") {
            recoveryOpen = true
        }
        if (!storage.busy && storage.bytes == null) TextButton(onClick = model::refresh) { Text("Check storage again") }
        storage.message?.let { Text(it, Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        SettingsHeading("Privacy")
        Text("Leaf reads your library offline. Books, annotations, and reading history are stored on this device. Leaf uses no accounts, tracking, or ads.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        HorizontalDivider()
        SettingsHeading("About")
        ListItem(headlineContent = { Text("Leaf version") }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
        SettingsAction("Open-source licenses") { licensesOpen = true }
        SettingsAction("Leaf on GitHub", "Opens your browser") {
            linkUnavailable = try {
                uriHandler.openUri("https://github.com/shivasathvik44/LEAF")
                false
            } catch (_: IllegalArgumentException) {
                true
            } catch (_: ActivityNotFoundException) {
                true
            }
        }
        if (linkUnavailable) Text("A browser could not open the repository.", Modifier.padding(16.dp))
    }
    if (recoveryOpen) RecoveryDialog(storage.recovered, storage.busy, onDismiss = { recoveryOpen = false }) { token ->
        recoveryOpen = false
        model.restore(token)
    }
}

@Composable
private fun SettingsHeading(text: String, primary: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
        style = if (primary) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
}

@Composable
private fun SettingsAction(title: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
    )
}

@Composable
private fun RecoveryDialog(copies: List<RecoveredBookCopy>, busy: Boolean, onDismiss: () -> Unit, onRestore: (String) -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Recovered books") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Leaf kept these copies after an interrupted import or storage problem. Select a copy to validate it and return it to your library.")
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(copies, key = { _, copy -> copy.token }) { index, copy ->
                    val size = Formatter.formatFileSize(context, copy.bytes)
                    SettingsAction("Restore ${copy.format.name} copy ${index + 1}", "$size · ${dates.format(Date(copy.modifiedAt))}", enabled = !busy) {
                        onRestore(copy.token)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun PreferenceToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked, onCheckedChange = null) },
    )
}

@Composable
private fun <T> PreferenceChoice(title: String, value: T, options: List<Pair<T, String>>, select: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsAction(title, options.firstOrNull { it.first == value }?.second ?: "Custom") { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp).selectableGroup()) {
                items(options) { (choice, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = value == choice, role = Role.RadioButton, onClick = {
                            select(choice)
                            open = false
                        }).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = value == choice, onClick = null)
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LicenseNotices(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val assetNames by produceState<List<String>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            try { assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }
                .sortedWith(compareBy({ it != "index.txt" }, { it })) }
            catch (_: IOException) { emptyList() }
        }
    }
    val contents by produceState<List<String>?>(null, assets, selected) {
        value = null
        val name = selected ?: return@produceState
        value = withContext(Dispatchers.IO) {
            try { assets.open("licenses/$name").bufferedReader().use { it.readText().split("\n\n") } }
            catch (_: IOException) { emptyList() }
        }
    }
    val back = { if (selected == null) onBack() else selected = null }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = back) { Text(if (selected == null) "Back to Settings" else "Back to licenses") }
        SettingsHeading(selected?.let(::noticeTitle) ?: "Open-source licenses", primary = true)
        if (selected == null) {
            val names = assetNames
            if (names == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else if (names.isEmpty()) Text("The bundled license list could not be loaded.")
            else LazyColumn { items(names, key = { it }) { name -> SettingsAction(noticeTitle(name)) { selected = name } } }
        } else {
            val paragraphs = contents
            if (paragraphs == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            else if (paragraphs.isEmpty()) Text("This bundled notice could not be loaded.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(paragraphs) { paragraph -> Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private fun Enum<*>.label() = name.lowercase().replaceFirstChar(Char::titlecase)
private fun noticeTitle(name: String) = if (name == "index.txt") "License index" else name.removeSuffix(".txt").replace('-', ' ')
