@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.items

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Shape
import io.github.takosasidev.shelftally.ui.components.groupShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.ItemFilter
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.data.PhotoStore
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.ItemIconLabels
import io.github.takosasidev.shelftally.ui.components.ItemIcons
import io.github.takosasidev.shelftally.ui.components.ItemThumb
import io.github.takosasidev.shelftally.ui.nav.Routes
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 単位の候補。ここに無い単位も自由に入力できる。 */
private val UnitSuggestions = listOf("個", "本", "袋", "パック", "箱", "枚", "ロール", "缶", "g", "mL")

/**
 * 品目の新規登録(FR-01)と編集(FR-27)。写真(FR-07)、保管場所(FR-08)、期限(FR-09)、定期購入(FR-12)もここで決める。
 * [barcode] はスキャンから引き継ぐ番号(FR-06)。
 */
@Composable
fun ItemEditScreen(vm: ShelfViewModel, itemId: Long, barcode: String, onDone: () -> Unit, onBack: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val isNew = itemId == Routes.NEW_ID
    val original = if (isNew) null else items.firstOrNull { it.id == itemId }
    if (!isNew && original == null) {
        // 読み込みの途中。欄の初期値は品目が届いてから決める
        Box(Modifier.fillMaxSize())
        return
    }
    EditForm(vm, original, barcode, categories, ItemFilter.locations(items), onDone, onBack)
}

@Composable
private fun EditForm(
    vm: ShelfViewModel,
    original: Item?,
    barcode: String,
    categories: List<Category>,
    locations: List<String>,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var quantityText by rememberSaveable { mutableStateOf(original?.let { Format.quantity(it.currentQuantity) }.orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(original?.unit ?: "個") }
    var thresholdText by rememberSaveable { mutableStateOf(original?.let { Format.quantity(it.lowStockThreshold) } ?: "1") }
    var categoryId by rememberSaveable { mutableStateOf(original?.categoryId) }
    var location by rememberSaveable { mutableStateOf(original?.locationTag.orEmpty()) }
    var expiryText by rememberSaveable { mutableStateOf(original?.expiryDate?.toString().orEmpty()) }
    var barcodeText by rememberSaveable { mutableStateOf(original?.barcodeValue ?: barcode) }
    var intervalText by rememberSaveable { mutableStateOf(original?.recurringPurchaseIntervalDays?.toString().orEmpty()) }
    var iconKey by rememberSaveable { mutableStateOf(original?.iconKey) }
    var photo by rememberSaveable { mutableStateOf(original?.photoUri) }
    var showDatePicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val quantity = Format.parseQuantity(quantityText)
    val threshold = Format.parseQuantity(thresholdText.ifBlank { "0" })
    val expiry = runCatching { LocalDate.parse(expiryText) }.getOrNull()
    val valid = name.isNotBlank() && quantity != null && threshold != null

    // 写真は取り込むときに縮めて保存する。保存せずに戻った場合の残りは、次の写真の掃除で消える
    val importPhoto: (android.net.Uri) -> Unit = { uri ->
        scope.launch {
            val saved = PhotoStore.save(context, uri)
            if (saved != null) photo = saved else snackbar.showSnackbar("写真を読み込めませんでした")
        }
    }
    val captureUri = remember { runCatching { PhotoStore.captureUri(context) }.getOrNull() }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && captureUri != null) importPhoto(captureUri)
    }
    // CAMERA の権限を宣言しているアプリは、許可が無いままカメラのアプリを呼ぶと例外になるので、先に許可をもらう
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && captureUri != null) {
            runCatching { takePicture.launch(captureUri) }
                .onFailure { scope.launch { snackbar.showSnackbar("カメラのアプリを開けませんでした") } }
        } else {
            scope.launch { snackbar.showSnackbar("撮影にはカメラの許可が要ります") }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importPhoto(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "品目を追加" else "品目を編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
                    TextButton(
                        enabled = valid && !saving,
                        onClick = {
                            saving = true
                            val item = (original ?: Item(name = "", createdAt = Instant.now())).copy(
                                name = name.trim(),
                                barcodeValue = barcodeText.trim().ifEmpty { null },
                                categoryId = categoryId,
                                locationTag = location.trim().ifEmpty { null },
                                currentQuantity = quantity ?: 0.0,
                                unit = unit.trim().ifEmpty { "個" },
                                lowStockThreshold = threshold ?: 0.0,
                                expiryDate = expiry,
                                photoUri = photo,
                                recurringPurchaseIntervalDays = intervalText.toIntOrNull()?.takeIf { it > 0 },
                                iconKey = iconKey,
                            )
                            scope.launch {
                                vm.repo.saveItem(item)
                                onDone()
                            }
                        },
                    ) { Text("保存") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // 入力欄は意味ごとのまとまりに分ける(端だけ大きく丸めたまとまり)
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          FormSection("写真とアイコン", groupShape(0, 5)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemThumb(
                    Item(name = name, photoUri = photo, iconKey = iconKey, createdAt = Instant.EPOCH),
                    categories.firstOrNull { it.id == categoryId },
                    72.dp,
                )
                FilledTonalButton(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Text("撮る", Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Text("選ぶ", Modifier.padding(start = 6.dp))
                }
                if (photo != null) {
                    IconButton(onClick = { photo = null }) { Icon(Icons.Filled.Close, contentDescription = "写真を外す") }
                }
            }
            // アイコン(写真が無いときに出る)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemIcons.forEachIndexed { index, (key, icon) ->
                    FilterChip(
                        // 既定(未設定)は先頭のアイコン
                        selected = iconKey == key || (iconKey == null && index == 0),
                        onClick = { iconKey = key },
                        // 保存するキーは英語なので、読み上げには日本語の名前を渡す
                        label = { Icon(icon, contentDescription = ItemIconLabels[key] ?: key) },
                    )
                }
            }
          }

          FormSection("基本", groupShape(1, 5)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("品名(必須)") },
                singleLine = true,
                isError = name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("今の数量(必須)") },
                    singleLine = true,
                    isError = quantity == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                SuggestField(unit, { unit = it }, "単位", UnitSuggestions, Modifier.weight(1f))
            }

            OutlinedTextField(
                value = thresholdText,
                onValueChange = { thresholdText = it },
                label = { Text("この数量以下でお知らせ") },
                supportingText = { Text("残りがこの数量以下になると、通知して買い物リストに入れます") },
                singleLine = true,
                isError = threshold == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
          }

          FormSection("分類と場所", groupShape(2, 5)) {
            PickField(
                label = "カテゴリ",
                text = categories.firstOrNull { it.id == categoryId }?.name ?: "未分類",
                options = listOf<Pair<Long?, String>>(null to "未分類") + categories.map { it.id to it.name },
                onPick = { categoryId = it },
            )

            // FR-08: 前に入れた保管場所を候補に出す
            SuggestField(location, { location = it }, "保管場所(例: キッチン)", locations, Modifier.fillMaxWidth())
          }

          FormSection("期限と定期購入", groupShape(3, 5)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Filled.Event, contentDescription = null)
                    Text(if (expiry != null) "期限 $expiry" else "賞味期限・使用期限を決める", Modifier.padding(start = 6.dp))
                }
                if (expiry != null) {
                    IconButton(onClick = { expiryText = "" }) { Icon(Icons.Filled.Close, contentDescription = "期限を消す") }
                }
            }

            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it.filter(Char::isDigit).take(4) },
                label = { Text("定期購入のお知らせ(日ごと・任意)") },
                supportingText = { Text("在庫の数に関わらず、この日数ごとに「そろそろ購入時期です」と通知します") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
          }

          FormSection("バーコード", groupShape(4, 5)) {
            OutlinedTextField(
                value = barcodeText,
                onValueChange = { barcodeText = it },
                label = { Text("バーコードの番号(任意)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
          }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (expiry ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        expiryText = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("キャンセル") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** 入力欄のまとまり。見出しと中身を、角の丸い面に載せる。 */
@Composable
private fun FormSection(title: String, shape: Shape, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** 自由に入力でき、候補からも選べる欄。 */
@Composable
private fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // 入力に合う候補。合う物が無ければ全部(「個」と入っている欄を押したときに、ほかの単位を選べるように)
    val others = suggestions.filter { it != value }
    val shown = others.filter { it.contains(value, ignoreCase = true) }.ifEmpty { others }.take(8)
    ExposedDropdownMenuBox(expanded = expanded && shown.isNotEmpty(), onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = { expanded = false }) {
            shown.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 決まった選択肢から選ぶ欄。 */
@Composable
private fun <T> PickField(label: String, text: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionText) ->
                DropdownMenuItem(
                    text = { Text(optionText) },
                    onClick = {
                        onPick(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
