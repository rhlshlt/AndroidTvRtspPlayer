package io.github.rhlshlt.tvrtsp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.rhlshlt.tvrtsp.ui.theme.TvColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moves focus to this target once it is attached. Items of a lazy list attach a frame after the
 * screen's first composition, and requesting focus before that throws.
 */
suspend fun FocusRequester.focusWhenReady() {
    repeat(20) {
        try {
            requestFocus()
            return
        } catch (_: IllegalStateException) {
            delay(50)
        }
    }
}

/** Overscan-safe padding from the plan: 48 dp left and right, 27 dp top and bottom. */
val SafeAreaPadding = Modifier.padding(horizontal = 48.dp, vertical = 27.dp)

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TvColors.Muted)
    }
}

/** A focusable menu row. */
@Composable
fun MenuItem(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = modifier,
    )
}

/** A button showing "label: value"; pressing it changes the value. */
@Composable
fun OptionButton(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text("$label: $value")
    }
}

/**
 * A text field that works with a remote: Up/Down leave the field, OK opens the on-screen keyboard.
 * Phase 0 keeps entered values in memory only.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (focused) colors.primary else TvColors.Muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 20.sp),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            keyboard?.show()
                            true
                        }
                        else -> false
                    }
                }
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .border(if (focused) 3.dp else 1.dp, if (focused) colors.primary else TvColors.Muted, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = TvColors.Muted)
    }
}

/** Long text that scrolls with Up/Down while focused; at either end, focus moves on as usual. */
@Composable
fun ScrollableText(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 15.sp) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionDown && scroll.value < scroll.maxValue -> {
                        scope.launch { scroll.animateScrollBy(320f) }
                        true
                    }
                    e.key == Key.DirectionUp && scroll.value > 0 -> {
                        scope.launch { scroll.animateScrollBy(-320f) }
                        true
                    }
                    else -> false
                }
            }
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else TvColors.Muted, RoundedCornerShape(8.dp))
            .focusable()
            .verticalScroll(scroll)
            .padding(16.dp),
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = fontSize)
    }
}

/**
 * Wall clock with tenths of a second, redrawn 10 times a second. Point the camera at the TV and
 * photograph both: the gap between this clock and the camera's own time stamp in the picture is the
 * glass-to-glass delay. It used to redraw every frame, which looked like the app's biggest CPU cost
 * on the first test TV (about 48% of a core with nothing playing); the camera's time stamp shows
 * whole seconds, so tenths are precise enough.
 */
@Composable
fun LatencyClock(modifier: Modifier = Modifier, fontSize: TextUnit = 44.sp) {
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
    val text by produceState("") {
        while (true) {
            val now = System.currentTimeMillis()
            value = format.format(Date(now)) + "." + (now % 1_000 / 100)
            delay(100 - now % 100) // wake at the next tenth
        }
    }
    Text(
        text = text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
    )
}

/** A row of buttons, spaced for a remote. */
@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) { content() }
}
