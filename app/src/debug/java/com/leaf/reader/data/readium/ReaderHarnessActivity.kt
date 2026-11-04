package com.leaf.reader.data.readium

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.leaf.reader.R
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.ReaderPreferences
import java.io.File
import kotlinx.coroutines.launch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.leaf.reader.data.repository.AnnotationRepository
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.ReadingRepository
import com.leaf.reader.data.repository.PreferencesRepository

/** Debug-only test entry point. It cannot be launched by another application. */
class ReaderHarnessActivity : FragmentActivity() {
    var document: ReaderDocument? = null
        private set
    var failure: String? = null
        private set
    private var preferences = ReaderPreferences()
    private var host: ReaderHostFragment? = null
    private var ownsDocument = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = R.id.reader_harness_container })
        if (intent.getBooleanExtra("externalDocument", false)) return
        val file = File(requireNotNull(intent.getStringExtra("fixturePath")))
        val format = PublicationFormat.valueOf(requireNotNull(intent.getStringExtra("fixtureFormat")))
        val locator = savedInstanceState?.getString("locatorJson") ?: intent.getStringExtra("locatorJson")
        lifecycleScope.launch {
            try {
                val bookId = intent.getStringExtra("bookId")
                val dependencies = if (bookId != null) EntryPointAccessors.fromApplication(applicationContext, ReaderHarnessDependencies::class.java) else null
                val restored = locator ?: bookId?.let { dependencies?.reading()?.getProgress(it)?.locatorJson }
                val publication = PublicationRepository(applicationContext).open(file)
                val opened = ReaderDocument(publication, format, restored)
                document = opened
                val fragment = (supportFragmentManager.findFragmentByTag("reader-host") as? ReaderHostFragment)
                    ?: ReaderHostFragment().also {
                        supportFragmentManager.beginTransaction().replace(R.id.reader_harness_container, it, "reader-host").commitNow()
                    }
                host = fragment
                fragment.onError = { failure = it }
                fragment.bind(opened, preferences)
                if (bookId != null && dependencies != null) {
                    launch { dependencies.annotations().highlights(bookId).collect { opened.applyHighlights(it) } }
                    launch { opened.location.collect { location -> if (location != null) dependencies.reading().saveProgress(bookId, location.locatorJson, location.progression) } }
                }
            } catch (error: Exception) { failure = "Publication could not be opened by the reader harness." }
        }
    }

    fun bindExternal(document: ReaderDocument, preferences: ReaderPreferences = ReaderPreferences()) {
        if (this.document !== document) {
            supportFragmentManager.findFragmentByTag("reader-host")?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
            this.document = document
            ownsDocument = false
            val fragment = ReaderHostFragment()
            supportFragmentManager.beginTransaction().replace(R.id.reader_harness_container, fragment, "reader-host").commitNow()
            fragment.onError = { failure = it }
            host = fragment
        }
        host?.bind(document, preferences)
    }

    fun typography(preferences: ReaderPreferences) {
        this.preferences = preferences
        document?.let { host?.bind(it, preferences) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        document?.currentLocation()?.locatorJson?.let { outState.putString("locatorJson", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ownsDocument) document?.releaseOwner()
        document = null
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReaderHarnessDependencies {
    fun annotations(): AnnotationRepository
    fun books(): BookRepository
    fun reading(): ReadingRepository
    fun preferences(): PreferencesRepository
}
