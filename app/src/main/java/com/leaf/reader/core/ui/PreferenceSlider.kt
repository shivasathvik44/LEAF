package com.leaf.reader.core.ui

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun PreferenceSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onChange(draft.toDouble().coerceIn(range)) },
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        modifier = Modifier.semantics { contentDescription = label },
    )
}
