package com.gametimer.parent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** A choice from a short list as a dropdown (one field with the current value), instead of a column of radio buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open && enabled, onExpandedChange = { if (enabled) open = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && enabled) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; if (value != selected) onSelect(value) },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * The state colours, the same on every screen: running / started green, changed by a parent blue,
 * time up orange, alerts (tamper, timer closed) red. Fixed hues, so they mean the same in light and dark.
 */
data class StateColors(val good: Color, val changed: Color, val timeUp: Color, val alert: Color, val neutral: Color) {
    companion object {
        @Composable
        fun current(): StateColors = if (isSystemInDarkTheme())
            StateColors(Color(0xFF66BB6A), Color(0xFF64B5F6), Color(0xFFFFA726), Color(0xFFEF5350), MaterialTheme.colorScheme.onSurfaceVariant)
        else
            StateColors(Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFE65100), Color(0xFFC62828), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
