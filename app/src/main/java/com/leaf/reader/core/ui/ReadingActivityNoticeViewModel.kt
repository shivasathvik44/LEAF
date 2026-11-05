package com.leaf.reader.core.ui

import androidx.lifecycle.ViewModel
import com.leaf.reader.data.repository.ReadingActivityRecorderFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReadingActivityNoticeViewModel @Inject constructor(private val recorders: ReadingActivityRecorderFactory) : ViewModel() {
    val message = recorders.pendingNotice
    fun dismiss() = recorders.dismissNotice()
}
