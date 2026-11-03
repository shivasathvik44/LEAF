package com.leaf.reader.domain.usecase

import android.net.Uri
import com.leaf.reader.data.repository.BookImportRepository
import com.leaf.reader.data.repository.ImportResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

data class BookImportOutcome(val uri: Uri, val result: ImportResult? = null, val message: String? = null)

class ImportBooksUseCase @Inject constructor(private val repository: BookImportRepository) {
    suspend operator fun invoke(uris: List<Uri>): List<BookImportOutcome> = uris.map { uri ->
        try { BookImportOutcome(uri, result = repository.importBook(uri)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { BookImportOutcome(uri, message = failure.message ?: "This book could not be imported. Select it again to retry.") }
    }
}
