package com.gametimer.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.graphics.luminance

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
        fun current(): StateColors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
            StateColors(Color(0xFF66BB6A), Color(0xFF64B5F6), Color(0xFFFFA726), Color(0xFFEF5350), MaterialTheme.colorScheme.onSurfaceVariant)
        else
            StateColors(Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFE65100), Color(0xFFC62828), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A number picked with − / +, the way the big parental apps set minutes; tapping the number lets you
 * type an exact value. [stepOf] gives the step at a value (bigger steps for bigger numbers).
 */
@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: (Int) -> String = { it.toString() },
    stepOf: (Int) -> Int = { 1 },
    isError: Boolean = false,
    onChange: (Int) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChange(stepDown(value, stepOf).coerceIn(range)) }, enabled = enabled && value > range.first) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(text(value), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 72.dp).clickable(enabled = enabled) { typing = true }.padding(horizontal = 8.dp, vertical = 10.dp))
            FilledTonalIconButton(onClick = { onChange(stepUp(value, stepOf).coerceIn(range)) }, enabled = enabled && value < range.last) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    if (typing) NumberDialog(label, value, range, onDismiss = { typing = false }) { typing = false; onChange(it) }
}

/** − / + land on the step grid: from 62 with steps of 15 down is 60, up is 75; on the grid they move a whole step. */
internal fun stepUp(value: Int, stepOf: (Int) -> Int): Int {
    val step = stepOf(value).coerceAtLeast(1)
    return (Math.floorDiv(value, step) + 1) * step
}

internal fun stepDown(value: Int, stepOf: (Int) -> Int): Int {
    val step = stepOf(value - 1).coerceAtLeast(1)
    val off = Math.floorMod(value, step)
    return if (off == 0) value - step else value - off
}

/** Typing an exact number in [range]; OK only while the text is a number in range that [accept]s. */
@Composable
fun NumberDialog(
    title: String,
    initial: Int,
    range: IntRange,
    confirmText: String? = null,
    accept: (Int) -> Boolean = { true },
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val number = text.toIntOrNull()?.takeIf { it in range && accept(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { v -> text = v.filter { it.isDigit() }.take(range.last.toString().length) }, singleLine = true,
                isError = number == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("${range.first}–${range.last}") })
        },
        confirmButton = { TextButton(onClick = { number?.let(onConfirm) }, enabled = number != null) { Text(confirmText ?: stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Steps for minutes: 1 up to 10, 5 up to an hour, then 15. */
fun minuteStep(value: Int): Int = when {
    value < 10 -> 1
    value < 60 -> 5
    else -> 15
}

private val PALETTE = listOf("#4CAF50", "#8BC34A", "#2196F3", "#9C27B0", "#FFEB3B", "#FFC107", "#FF9800", "#F44336", "#E91E63", "#FFFFFF")

private fun hexColor(hex: String?): Color? =
    hex?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }?.let { Color(android.graphics.Color.parseColor(it)) }

/** A colour from a row of swatches, or any colour typed as #RRGGBB under "Custom". */
@Composable
fun ColorSwatches(label: String, value: String, onChange: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val swatches = if (PALETTE.any { it.equals(value, ignoreCase = true) } || hexColor(value) == null) PALETTE else listOf(value.uppercase()) + PALETTE
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            swatches.forEach { hex ->
                val selected = hex.equals(value, ignoreCase = true)
                // The whole 40 dp circle is the touch target; TalkBack reads the colour code and the selection
                Box(Modifier.size(40.dp).clip(CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton) { if (!selected) onChange(hex) }
                    .semantics { contentDescription = hex }
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .padding(if (selected) 6.dp else 3.dp)
                    .background(hexColor(hex) ?: Color.Transparent, CircleShape))
            }
            AssistChip(onClick = { custom = true }, label = { Text(stringResource(R.string.color_custom)) })
        }
    }
    if (custom) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(label) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(hexColor(text) ?: Color.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    OutlinedTextField(text, { v ->
                        val hex = v.trim().uppercase().filter { it == '#' || it.isDigit() || it in 'A'..'F' }
                        text = ("#" + hex.removePrefix("#").replace("#", "")).take(7)
                    }, singleLine = true, isError = hexColor(text) == null, placeholder = { Text("#RRGGBB") })
                }
            },
            confirmButton = { TextButton(onClick = { custom = false; onChange(text) }, enabled = hexColor(text) != null) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The Material clock picker in a dialog; [minutes] since midnight in and out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minutes / 60, minutes % 60, DateFormat.is24HourFormat(context))
    // Always the vertical clock, scrollable: in landscape the wide layout doesn't fit a dialog
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state, layoutType = TimePickerLayoutType.Vertical)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
