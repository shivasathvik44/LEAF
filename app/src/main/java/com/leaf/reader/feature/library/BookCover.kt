package com.leaf.reader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.leaf.reader.domain.model.Book
import java.io.File

@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val palette = listOf(0xFF344A3E, 0xFF5C493A, 0xFF3F4A59, 0xFF5D4247)
    Box(modifier.background(Color(palette[(book.id.hashCode() and Int.MAX_VALUE) % palette.size])), contentAlignment = Alignment.Center) {
        // The typographic fallback stays beneath the image, including missing/stale cover files.
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("LEAF LIBRARY", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEBE7D6))
            HorizontalDivider(color = Color(0x99EBE7D6))
            Text(book.title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleLarge, maxLines = 5,
                overflow = TextOverflow.Ellipsis, color = Color(0xFFF5F0DF))
            Text(book.author, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFFEBE7D6))
        }
        book.coverPath?.let { path ->
            AsyncImage(model = File(path), contentDescription = "Cover of ${book.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
