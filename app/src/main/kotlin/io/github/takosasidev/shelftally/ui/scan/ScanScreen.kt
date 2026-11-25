@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.barcode.BarcodeDecoder
import io.github.takosasidev.shelftally.core.barcode.Luma
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.EmptyState
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** この時間だけ読めなければ「手入力で登録」を出す(汚れたバーコードで止まらないように)。 */
private const val SLOW_MS = 8_000L

/**
 * FR-06: バーコードを読む。読めた番号で登録済みの品目を探し、
 * 1 件ならその品目の画面へ、無ければ番号を引き継いだ新規登録へ、複数あれば選ばせる。
 * 画像は端末の中だけで処理する(CameraX の解析の画像 → :core の BarcodeDecoder)。外へは何も送らない。
 */
@Composable
fun ScanScreen(vm: ShelfViewModel, onBack: () -> Unit, onFound: (Long) -> Unit, onNew: (String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val items by vm.items.collectAsStateWithLifecycle()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    /** 読めた番号。入っている間は次の読み取りを受けない。 */
    var code by remember { mutableStateOf<String?>(null) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) {
            delay(SLOW_MS)
            slow = true
        }
    }

    val matches = code?.let { value -> items.filter { it.barcodeValue == value } }.orEmpty()
    val currentMatches by rememberUpdatedState(matches)
    LaunchedEffect(code) {
        val value = code ?: return@LaunchedEffect
        // 「読めた」をはっきり返す
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (currentMatches.size) {
            0 -> onNew(value)
            1 -> onFound(currentMatches.first().id)
            else -> Unit // 下の選ぶ画面に任せる
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("バーコードを読む") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        if (!granted) {
            EmptyState(
                icon = Icons.Filled.QrCodeScanner,
                title = "カメラの許可が要ります",
                body = "バーコードを読むためだけにカメラを使います。画像は端末の中で処理し、保存も送信もしません。",
                modifier = Modifier.padding(padding),
                actionLabel = "手入力で登録",
                onAction = { onNew("") },
            )
        } else {
            Box(Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
                CameraPreview(onDecoded = { if (code == null) code = it }, modifier = Modifier.fillMaxSize())
                ScanFrame(Modifier.align(Alignment.Center))
                Text(
                    "バーコードを枠に合わせてください",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                )
                if (slow) {
                    Button(onClick = { onNew("") }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                        Text("手入力で登録")
                    }
                }
            }
        }
    }

    // 同じ番号の品目が複数ある(同じ商品を別の名前で登録した)ときは選ばせる
    if (matches.size > 1) {
        AlertDialog(
            onDismissRequest = { code = null },
            title = { Text("どの品目ですか") },
            text = {
                Column {
                    matches.forEach { item ->
                        Text(
                            "${item.name}(残り ${Format.quantity(item.currentQuantity, item.unit)})",
                            modifier = Modifier.fillMaxWidth().clickable { onFound(item.id) }.padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { code = null }) { Text("読み直す") } },
        )
    }
}

/** 読み取りの枠と、上下に動く線。 */
@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "scan")
    val position by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "scanLine",
    )
    Box(
        modifier.fillMaxWidth(0.8f).aspectRatio(1.6f)
            .clip(RoundedCornerShape(16.dp))
            .border(3.dp, color, RoundedCornerShape(16.dp))
            .drawBehind {
                val y = size.height * position
                drawLine(color, Offset(24f, y), Offset(size.width - 24f, y), strokeWidth = 4f)
            },
    )
}

/**
 * カメラのプレビューと解析。書き方は DeskSlate の probe と同じ(1280x720 に近い解像度、YUV、最新の 1 枚だけ)。
 * 解析は Y 面(planes[0])だけを使う。
 */
@Composable
private fun CameraPreview(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val decoder = BarcodeDecoder()
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // カメラを開けない端末でも落とさない(手入力の道が残る)
            runCatching {
                val p = future.get()
                provider = p
                p.unbindAll()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    // 解析のスレッドで呼ばれる。必ず close する(閉じないと次の画像が来ない)
                    try {
                        val plane = image.planes[0]
                        val y = Luma.packY(plane.buffer, image.width, image.height, plane.rowStride)
                        val text = decoder.decode(y, image.width, image.height)
                        if (text != null) previewView.post { currentOnDecoded(text) }
                    } catch (e: Exception) {
                        // 1 枚読めなくても次の画像で試す
                    } finally {
                        image.close()
                    }
                }
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
