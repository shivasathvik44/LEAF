package com.leaf.reader.core.ui

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** UI lifecycle/window eligibility only; the recorder independently checks device lock/power. */
@Composable
fun ReadingActivityEligibilityEffect(
    modalOpen: Boolean,
    onEligibilityChanged: (Boolean) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val notify by rememberUpdatedState(onEligibilityChanged)
    DisposableEffect(owner, view, modalOpen) {
        val update = {
            notify(!modalOpen &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus())
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> update() }
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { update() }
        val tree = view.viewTreeObserver
        owner.lifecycle.addObserver(lifecycleObserver)
        tree.addOnWindowFocusChangeListener(focusObserver)
        update()
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(focusObserver)
            notify(false)
        }
    }
}
