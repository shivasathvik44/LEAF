package com.leaf.reader.domain.usecase

import com.leaf.reader.data.local.files.ManagedBookFiles
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.PreferencesRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class BookRemovalResult(val removed: Boolean, val managedCopiesDeleted: Boolean, val copiesRetainedForRecovery: Boolean)

class BookRemovalException(cause: Throwable? = null) : IOException(
    "This book could not be removed. Its private copy has been kept. Please try again.", cause,
)

class RemoveBookUseCase @Inject constructor(
    private val books: BookRepository,
    private val files: ManagedBookFiles,
    private val preferences: PreferencesRepository,
) {
    /** False removes the entry but keeps private copies in recovery; source documents are never touched. */
    suspend operator fun invoke(bookId: String, deleteManagedCopyConfirmed: Boolean): BookRemovalResult = try {
        withContext(Dispatchers.IO) {
            files.exclusive {
                val book = books.get(bookId) ?: return@exclusive BookRemovalResult(false, false, false)
                books.removeRecord(bookId)
                withContext(NonCancellable) {
                    var handled = true
                    try {
                        handled = if (deleteManagedCopyConfirmed) deleteBookCopy(book.storagePath) else quarantineBookCopy(book.storagePath)
                        book.coverPath?.let { path ->
                            handled = (if (deleteManagedCopyConfirmed) deleteCoverCopy(path) else quarantineCoverCopy(path)) && handled
                        }
                    } catch (_: Exception) { handled = false }
                    try { preferences.resetReaderPrefs(bookId) } catch (_: Exception) { /* Never undo a completed removal because a preference file is unavailable. */ }
                    BookRemovalResult(true, deleteManagedCopyConfirmed && handled, !deleteManagedCopyConfirmed || !handled)
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw BookRemovalException(failure) }
}
