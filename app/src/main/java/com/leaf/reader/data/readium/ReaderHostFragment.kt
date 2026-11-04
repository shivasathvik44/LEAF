package com.leaf.reader.data.readium

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.lifecycleScope
import com.leaf.reader.R
import com.leaf.reader.domain.model.ReaderPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Navigator

/** Reopens by ID outside this Fragment; never asks Android to reconstruct a publication. */
class ReaderHostFragment : Fragment() {
    private var document: ReaderDocument? = null
    private var retainedDocument: ReaderDocument? = null
    private var preferences = ReaderPreferences()
    private var nativeFragment: Fragment? = null
    private var observer: Job? = null
    private var decorationRestore: Job? = null
    private var attachmentFailed = false
    var onTap: () -> Unit = {}
    var onInteraction: () -> Unit = {}
    var onError: (String) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Native navigators need an opened publication in their constructors. On process
        // restoration, consume their old state with inert placeholders, before any native view
        // is created. The ViewModel then supplies a freshly opened publication/full locator.
        childFragmentManager.fragmentFactory = ReaderRestorationFactory()
        super.onCreate(savedInstanceState)
        removeRestoredChildren()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.reader_navigator_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val navigator = nativeFragment as? Navigator
        if (navigator != null) observe(navigator) else attachIfReady()
    }

    fun bind(document: ReaderDocument, preferences: ReaderPreferences) {
        check(this.document == null || this.document === document) { "A reader host cannot switch publications." }
        this.document = document
        this.preferences = preferences
        val native = nativeFragment as? Navigator
        if (native != null) document.submitPreferences(native, preferences) else attachIfReady()
    }

    private fun attachIfReady() {
        val document = document ?: return
        if (!isAdded || view == null || childFragmentManager.isStateSaved || nativeFragment != null || attachmentFailed) return
        if (retainedDocument == null) { document.retainHost(); retainedDocument = document }
        try {
            val fragment = document.createNavigator(requireContext().classLoader, preferences,
                onTap = { onTap() }, onInteraction = { onInteraction() }, onError = { onError(it) })
            childFragmentManager.beginTransaction().replace(R.id.reader_navigator_container, fragment, "leaf-navigator").commitNow()
            nativeFragment = fragment
            val navigator = fragment as Navigator
            observe(navigator)
        } catch (error: Exception) {
            attachmentFailed = true
            // A partially added child may still own native resources. Keep the document lease
            // until the host and all its children are destroyed, even on construction failure.
            onError("The reader could not be started. Return to your library and try again.")
        }
    }

    private fun observe(navigator: Navigator) {
        val document = retainedDocument ?: return
        document.attach(navigator, viewLifecycleOwner.lifecycleScope)
        observer?.cancel()
        observer = viewLifecycleOwner.lifecycleScope.launch { navigator.currentLocator.collect(document::accept) }
        decorationRestore?.cancel()
        decorationRestore = viewLifecycleOwner.lifecycleScope.launch {
            try { document.restoreHighlights() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { onError("Saved highlights could not be displayed. Reopen the book to retry.") }
        }
    }

    override fun onResume() { super.onResume(); attachIfReady() }

    override fun onDestroyView() {
        (nativeFragment as? Navigator)?.let { retainedDocument?.accept(it.currentLocator.value) }
        observer?.cancel()
        observer = null
        decorationRestore?.cancel()
        decorationRestore = null
        (nativeFragment as? Navigator)?.let { retainedDocument?.detach(it) }
        super.onDestroyView()
    }

    override fun onDestroy() {
        // Fragment.performDestroy destroys the child manager first; native consumers have
        // relinquished their publication resources by the time this callback runs.
        nativeFragment = null
        retainedDocument?.releaseHost()
        retainedDocument = null
        document = null
        super.onDestroy()
    }

    private fun removeRestoredChildren() {
        val restored = childFragmentManager.fragments
        if (restored.isNotEmpty()) childFragmentManager.beginTransaction().apply { restored.forEach(::remove) }.commitNow()
    }
}

private class ReaderRestorationFactory : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        if (className.startsWith("org.readium.")) RestoredReaderPlaceholder() else super.instantiate(classLoader, className)
}

/** No view is created and nested native saved state is consumed before resuming. */
class RestoredReaderPlaceholder : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        childFragmentManager.fragmentFactory = ReaderRestorationFactory()
        super.onCreate(savedInstanceState)
        val children = childFragmentManager.fragments
        if (children.isNotEmpty()) childFragmentManager.beginTransaction().apply { children.forEach(::remove) }.commitNow()
    }
}
