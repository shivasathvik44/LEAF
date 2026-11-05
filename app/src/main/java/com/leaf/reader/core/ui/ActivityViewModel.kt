package com.leaf.reader.core.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

tailrec fun Context.leafActivity(): FragmentActivity = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.leafActivity()
    else -> error("Leaf screens require a FragmentActivity host.")
}

@Composable
inline fun <reified T : ViewModel> leafActivityViewModel(key: String): T {
    val activity = LocalContext.current.leafActivity()
    return viewModel(viewModelStoreOwner = activity, key = key, factory = activity.defaultViewModelProviderFactory)
}

/** Receives real reader input, including native PDF scrolling and hardware keys. */
interface ReaderInteractionHost {
    var onReadingInteraction: (() -> Unit)?
}
