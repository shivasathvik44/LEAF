package com.leaf.reader

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.reader.core.designsystem.LeafTheme
import com.leaf.reader.core.ui.ReaderInteractionHost
import com.leaf.reader.core.ui.ReadingActivityNoticeViewModel
import com.leaf.reader.domain.model.AppTheme
import com.leaf.reader.feature.library.LibraryViewModel
import com.leaf.reader.navigation.LeafApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity(), ReaderInteractionHost {
    override var onReadingInteraction: (() -> Unit)? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) onReadingInteraction?.invoke()
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) onReadingInteraction?.invoke()
        return super.dispatchGenericMotionEvent(event)
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        onReadingInteraction?.invoke()
    }
    private val library: LibraryViewModel by viewModels()
    private val activityNotices: ReadingActivityNoticeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val preferences by library.preferences.collectAsStateWithLifecycle()
            val dark = when (preferences.appTheme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LeafTheme(dark = dark, accent = Color(preferences.accent.toInt())) { LeafApp(library, activityNotices) }
        }
    }
}
