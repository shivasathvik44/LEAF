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
                val publication = PublicationRepository(applicationContext).open(file)
                val opened = ReaderDocument(publication, format, locator)
                document = opened
                val fragment = (supportFragmentManager.findFragmentByTag("reader-host") as? ReaderHostFragment)
                    ?: ReaderHostFragment().also {
                        supportFragmentManager.beginTransaction().replace(R.id.reader_harness_container, it, "reader-host").commitNow()
                    }
                host = fragment
                fragment.onError = { failure = it }
                fragment.bind(opened, preferences)
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
