package com.leaf.reader.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leaf.reader.domain.model.*

private val importTypes = arrayOf("application/epub+zip", "application/pdf", "application/octet-stream", "application/x-epub+zip")

@Composable
fun LibraryScreen(state: LibraryUiState, model: LibraryViewModel, onBook: (String) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::importBooks)
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Leaf", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif)
                Text("A little time for yourself.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { picker.launch(importTypes) }, enabled = !state.importing, modifier = Modifier.testTag("import-books")) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Import")
            }
        }
        OutlinedTextField(value = state.query, onValueChange = model::search, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Search your library") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("Sort: ${state.preferences.sort.displayName()}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    BookSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.displayName()) }, onClick = { model.sort(sort); sortMenu = false }) }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = model::toggleLayout) { Text(if (state.preferences.listView) "Grid view" else "List view") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(state.status == null, { model.filter(null) }, label = { Text("All") }) }
            items(ReadingStatus.entries) { status -> FilterChip(state.status == status, { model.filter(status) }, label = { Text(status.displayName()) }) }
        }
        if (state.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Text(state.importProgress.orEmpty(), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.loadError, color = MaterialTheme.colorScheme.error)
                Button(onClick = model::retry) { Text("Retry") }
            }
            state.books.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your next chapter starts here", fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineSmall)
                    Text("Import an EPUB or PDF. Your books stay private, right on this device.")
                    Button(onClick = { picker.launch(importTypes) }, enabled = !state.importing) { Text("Choose books") }
                }
            }
            else -> LibraryBooks(state, onBook)
        }
    }
}

@Composable
private fun LibraryBooks(state: LibraryUiState, onBook: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(if (state.preferences.listView) 1 else 2),
        contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.query.isBlank() && state.status == null) {
            val current = state.books.filter { it.status == ReadingStatus.READING }.maxByOrNull { it.lastOpenedAt ?: 0 }
            if (current != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Continue reading", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Card(onClick = { onBook(current.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BookCover(current, Modifier.width(76.dp).height(110.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(current.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                LinearProgressIndicator(progress = { current.progression.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text("${(current.progression * 100).toInt()}% read", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            val recent = state.books.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt }.take(5)
            if (recent.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recently opened", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recent, key = { it.id }) { book -> Column(Modifier.width(100.dp).clickable { onBook(book.id) }) {
                            BookCover(book, Modifier.fillMaxWidth().height(144.dp))
                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        } }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("All books · ${state.visibleBooks.size}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif) }
        if (state.visibleBooks.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No books match. Try another search or filter.") }
        items(state.visibleBooks, key = { it.id }) { book ->
            if (state.preferences.listView) Row(Modifier.fillMaxWidth().clickable { onBook(book.id) }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.width(65.dp).height(95.dp))
                Column(Modifier.weight(1f)) { BookCaption(book) }
            } else Column(Modifier.fillMaxWidth().clickable { onBook(book.id) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BookCover(book, Modifier.fillMaxWidth().aspectRatio(0.68f))
                BookCaption(book)
            }
        }
    }
}

@Composable
private fun BookCaption(book: Book) {
    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (book.progression > 0) LinearProgressIndicator(progress = { book.progression.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    Text("${book.format.name} · ${book.status.displayName()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
