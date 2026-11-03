package com.leaf.reader.feature.bookdetails

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.leaf.reader.domain.model.*
import com.leaf.reader.feature.library.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailsScreen(book: Book?, loading: Boolean, onBack: () -> Unit, onStatus: (ReadingStatus) -> Unit, onRemove: () -> Unit, onOpen: (() -> Unit)? = null) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Back to library") }
        if (loading) { CircularProgressIndicator(); return@Column }
        if (book == null) { Text("This book is no longer in your library."); return@Column }
        BookCover(book, Modifier.width(160.dp).height(235.dp).align(Alignment.CenterHorizontally))
        Text(book.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(book.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onOpen?.let { Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(if (book.status == ReadingStatus.READING) "Continue reading" else "Open book") } }
        Text("${book.format.name} · ${"%.1f".format(book.fileSize / 1048576.0)} MB · ${(book.progression * 100).toInt()}% read")
        Text("Imported ${DateFormat.getDateInstance().format(Date(book.importedAt))}", style = MaterialTheme.typography.bodySmall)
        Text("Reading status", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingStatus.entries.forEach { status -> FilterChip(book.status == status, { onStatus(status) }, label = { Text(status.displayName()) }) }
        }
        if (!book.description.isNullOrBlank()) {
            Text("About this book", style = MaterialTheme.typography.titleLarge)
            Text(remember(book.description) { HtmlCompat.fromHtml(book.description, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() })
        }
        TextButton(onClick = { confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove from library") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Remove this book?") },
        text = { Text("This removes Leaf’s private imported copy, reading position, bookmarks, highlights, notes and reading activity for this book. Your original document will stay where it is.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove private copy") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep book") } })
}
