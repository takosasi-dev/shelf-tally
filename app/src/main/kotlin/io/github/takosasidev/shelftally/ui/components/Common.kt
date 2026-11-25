// 複数の画面で使う部品。サムネイル・残量バー・増減ボタン・量の入力・空の状態。
package io.github.takosasidev.shelftally.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Soap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.calc.StockLevel
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.data.PhotoLoader
import io.github.takosasidev.shelftally.ui.theme.onColor
import io.github.takosasidev.shelftally.ui.theme.stockColor
import kotlin.math.roundToInt

/** `Item.iconKey` の候補。キーは DB に入るので、後から名前を変えない。先頭が既定。 */
val ItemIcons: List<Pair<String, ImageVector>> = listOf(
    "box" to Icons.Filled.Inventory2,
    "cleaning" to Icons.Filled.CleaningServices,
    "soap" to Icons.Filled.Soap,
    "food" to Icons.Filled.Restaurant,
    "drink" to Icons.Filled.LocalDrink,
    "kitchen" to Icons.Filled.Kitchen,
    "pet" to Icons.Filled.Pets,
    "medicine" to Icons.Filled.Medication,
)

fun itemIcon(key: String?): ImageVector = ItemIcons.firstOrNull { it.first == key }?.second ?: ItemIcons.first().second

/**
 * 品目のサムネイル。写真があれば写真、無い・消えている・壊れているなら、カテゴリの色の上にアイコン。
 * 写真のファイルが端末から消えていても落ちない(PhotoLoader が null を返す)。
 */
@Composable
fun ItemThumb(item: Item, category: Category?, size: Dp, modifier: Modifier = Modifier, reqPx: Int = 192) {
    val context = LocalContext.current
    val name = item.photoUri
    var bitmap by remember(name) { mutableStateOf<ImageBitmap?>(name?.let { PhotoLoader.cached(it, reqPx)?.asImageBitmap() }) }
    LaunchedEffect(name) {
        if (name != null && bitmap == null) bitmap = PhotoLoader.decodeSampled(context, name, reqPx)?.asImageBitmap()
    }
    val background = category?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                itemIcon(item.iconKey),
                contentDescription = null,
                tint = if (category != null) onColor(background) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * 残量バー。長さと色の変わり目を動かす(閾値を下回った瞬間が目に入るように)。
 * 色だけに頼らないよう、呼ぶ側で [stockText] の数と割合も並べる。
 */
@Composable
fun StockBar(item: Item, logs: List<ConsumptionLog>, modifier: Modifier = Modifier) {
    val ratio by animateFloatAsState(Stock.ratio(item, logs), tween(400), label = "stockRatio")
    val color by animateColorAsState(stockColor(Stock.level(item)), tween(400), label = "stockColor")
    LinearProgressIndicator(
        progress = { ratio },
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
    )
}

/** 「残り 2 個(33%)」「在庫切れ」。 */
fun stockText(item: Item, logs: List<ConsumptionLog>): String = when (Stock.level(item)) {
    StockLevel.OUT -> "在庫切れ"
    else -> "残り ${Format.quantity(item.currentQuantity, item.unit)}(${(Stock.ratio(item, logs) * 100).roundToInt()}%)"
}

/** ＋/− のボタン。押している間だけ少し縮む。タップの領域は 48dp。 */
@Composable
fun StepButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, label = "stepScale")
    FilledTonalIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.size(48.dp).scale(scale),
    ) { Icon(icon, contentDescription = description) }
}

/** 量(と、補充なら任意で価格)を入力させる。量り売りの単位の増減と、まとめての補充に使う。 */
@Composable
fun AmountDialog(
    title: String,
    unit: String,
    withPrice: Boolean,
    initialAmount: String = "",
    onConfirm: (amount: Double, price: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember { mutableStateOf(initialAmount) }
    var priceText by remember { mutableStateOf("") }
    val amount = Format.parseQuantity(amountText)?.takeIf { it > 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("量($unit)") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (withPrice) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.filter(Char::isDigit).take(8) },
                        label = { Text("払った金額(円・任意)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = amount != null, onClick = { amount?.let { onConfirm(it, priceText.toIntOrNull()) } }) {
                Text("決定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 何も無い画面で、次にすることを促す。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(actionLabel) }
        }
    }
}
