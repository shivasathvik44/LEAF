package com.leaf.reader.feature.shelves

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.reader.core.ui.leafActivityViewModel
import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.Shelf
import com.leaf.reader.domain.model.ShelfLimits
import com.leaf.reader.feature.library.BookCover

@Composable
fun ShelvesScreen(onBook: (String) -> Unit, onRead: (String) -> Unit) {
    val model = leafActivityViewModel<ShelvesViewModel>("leaf-shelves")
    val ui by model.state.collectAsStateWithLifecycle()
    val busy by model.membershipBusy.collectAsStateWithLifecycle()
    val deleting by model.deletionBusy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingBooks by rememberSaveable { mutableStateOf(false) }
    val shelf = ui.shelves.find { it.id == selectedId }
    BackHandler(enabled = selectedId != null && !addingBooks) { selectedId = null }
    LaunchedEffect(ui.loading, ui.shelves) {
        if (!ui.loading && ui.error == null && selectedId != null && shelf == null) selectedId = null
    }
    Column(Modifier.fillMaxSize().testTag("shelves-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectedId != null) IconButton(onClick = { selectedId = null }) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, "All shelves")
            }
            Text(shelf?.name ?: "Shelves", style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = { if (shelf == null) model.edit() else addingBooks = true }, enabled = !ui.loading && ui.error == null) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(if (shelf == null) "New shelf" else "Add books")
            }
        }
        if (shelf != null) {
            Text("${shelf.count} book${if (shelf.count == 1) "" else "s"}", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelLarge)
            if (shelf.description.isNotBlank()) Text(shelf.description, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        message?.let { text -> ShelfNotice(text) { model.message(null) } }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ui.error.orEmpty())
                Button(onClick = model::retry) { Text("Retry loading shelves") }
            }
            shelf != null -> {
                val members = ui.books.filter { it.id in shelf.bookIds }
                LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("shelf-books")) {
                    if (members.isEmpty()) item { Text("A little room for your favorite books. Tap Add books to fill this shelf.") }
                    items(members, key = { it.id }) { book ->
                        Card(Modifier.fillMaxWidth().testTag("shelf-book-${book.id}")) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BookCover(book, Modifier.width(64.dp).height(96.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(book.author, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row {
                                        TextButton(onClick = { onRead(book.id) }) { Text(if (book.progression > 0.0) "Continue reading" else "Read") }
                                        TextButton(onClick = { onBook(book.id) }) { Text("Details") }
                                    }
                                }
                                IconButton(onClick = { model.setMembership(shelf.id, book.id, false) }, enabled = "${shelf.id}/${book.id}" !in busy) {
                                    Icon(Icons.Default.Delete, "Remove ${book.title} from this shelf")
                                }
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("shelves-list")) {
                if (ui.shelves.isEmpty()) item { Text("Make room for your next good read. Create a shelf to organize books your way.") }
                items(ui.shelves, key = { it.id }) { item ->
                    var menu by remember(item.id) { mutableStateOf(false) }
                    Card(onClick = { selectedId = item.id }, modifier = Modifier.fillMaxWidth().testTag("shelf-${item.id}")) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ShelfCovers(ui.books.filter { it.id in item.bookIds })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.count} book${if (item.count == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
                                if (item.description.isNotBlank()) Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = item.id !in deleting) { Icon(Icons.Default.MoreVert, "Actions for ${item.name}") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Edit shelf") }, onClick = { menu = false; model.edit(item) })
                                    DropdownMenuItem(text = { Text("Delete shelf") }, onClick = { menu = false; deletingId = item.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    ShelfEditorDialog(model)
    ui.shelves.find { it.id == deletingId }?.let { item ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Delete ${item.name}?") },
            text = { Text("Only this shelf and its memberships will be removed. All books stay in your library.") },
            confirmButton = { TextButton(onClick = { model.delete(item.id); deletingId = null }, enabled = item.id !in deleting) { Text("Delete shelf") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Keep shelf") } })
    }
    if (addingBooks && shelf != null) {
        AlertDialog(onDismissRequest = { addingBooks = false }, title = { Text("Books on ${shelf.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (ui.books.isEmpty()) item { Text("Import books into your library first.") }
                    items(ui.books, key = { it.id }) { book ->
                        ShelfCheckboxRow(label = book.title, supporting = book.author,
                            checked = book.id in shelf.bookIds, enabled = "${shelf.id}/${book.id}" !in busy,
                            tag = "shelf-membership-${shelf.id}-${book.id}",
                            onCheckedChange = { model.setMembership(shelf.id, book.id, it) })
                    }
                }
            } }, confirmButton = { TextButton(onClick = { addingBooks = false }) { Text("Done") } })
    }
}

/** Book-details entry point: a book can independently belong to any number of shelves. */
@Composable
fun BookShelfPicker(bookId: String, onDismiss: () -> Unit) {
    val model = leafActivityViewModel<ShelvesViewModel>("leaf-shelves")
    val ui by model.state.collectAsStateWithLifecycle()
    val busy by model.membershipBusy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to shelves") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                ui.loading -> CircularProgressIndicator()
                ui.error != null -> { Text(ui.error.orEmpty()); TextButton(onClick = model::retry) { Text("Retry loading shelves") } }
                ui.books.none { it.id == bookId } -> Text("This book is no longer in your library.")
                else -> {
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        if (ui.shelves.isEmpty()) item { Text("Create a shelf for this book.") }
                        items(ui.shelves, key = { it.id }) { shelf ->
                            ShelfCheckboxRow(shelf.name, "${shelf.count} book${if (shelf.count == 1) "" else "s"}",
                                bookId in shelf.bookIds, "${shelf.id}/$bookId" !in busy,
                                "book-shelf-${shelf.id}") { model.setMembership(shelf.id, bookId, it) }
                        }
                    }
                    TextButton(onClick = { model.edit(addBookId = bookId) }) { Text("Create a new shelf") }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    ShelfEditorDialog(model)
}

@Composable
private fun ShelfCheckboxRow(label: String, supporting: String, checked: Boolean, enabled: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShelfCovers(books: List<Book>) {
    if (books.isNotEmpty()) Box(Modifier.width(72.dp).height(96.dp)) {
        books.take(3).reversed().forEachIndexed { index, book -> BookCover(book, Modifier.offset(x = (index * 6).dp).width(58.dp).height(88.dp)) }
    }
}

@Composable
private fun ShelfNotice(message: String, onDismiss: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun ShelfEditorDialog(model: ShelvesViewModel) {
    val editor by model.editor.collectAsStateWithLifecycle()
    editor?.let { form ->
        val nameTooLong = ShelfLimits.characters(form.name.trim()) > ShelfLimits.MAX_NAME_CHARACTERS
        val descriptionTooLong = !ShelfLimits.validDescription(form.description)
        AlertDialog(onDismissRequest = model::dismissEditor,
            properties = DialogProperties(dismissOnBackPress = !form.saving, dismissOnClickOutside = !form.saving),
            title = { Text(if (form.id == null) "New shelf" else "Edit shelf") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(form.name, model::editorName, label = { Text("Shelf name") }, singleLine = true, enabled = !form.saving,
                    isError = nameTooLong, supportingText = { Text(if (nameTooLong) "Shorten the name to 100 characters before saving." else "Up to 100 characters") },
                    modifier = Modifier.testTag("shelf-name"))
                OutlinedTextField(form.description, model::editorDescription, label = { Text("Description (optional)") }, enabled = !form.saving, minLines = 2, maxLines = 4,
                    isError = descriptionTooLong, supportingText = { Text(if (descriptionTooLong) "Shorten the description to 2000 characters before saving." else "Up to 2000 characters") },
                    modifier = Modifier.testTag("shelf-description"))
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("shelf-editor-error")) }
            } },
            confirmButton = { TextButton(onClick = model::saveEditor, enabled = ShelfLimits.validName(form.name) && !descriptionTooLong && !form.saving,
                modifier = Modifier.testTag("shelf-save")) { Text(if (form.saving) "Saving…" else "Save shelf") } },
            dismissButton = { TextButton(onClick = model::dismissEditor, enabled = !form.saving) { Text("Cancel") } })
    }
}
