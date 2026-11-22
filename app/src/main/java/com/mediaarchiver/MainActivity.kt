package com.mediaarchiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================ Helpers ============================

/**
 * Finds the first http(s) link inside any text and strips junk around it
 * (spaces, dashes, quotes, brackets, Arabic punctuation, invisible direction marks).
 */
fun cleanUrl(raw: String?): String? {
    if (raw == null) return null
    val match = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(raw) ?: return null
    val junk = "-–—.,;!?\"'()[]{}<>،؛"
    val cleaned = match.value.trim { ch ->
        ch.isWhitespace() || ch.category == CharCategory.FORMAT || junk.indexOf(ch) >= 0
    }
    return if (cleaned.length > 8) cleaned else null
}

private fun isPlausibleUrl(url: String): Boolean {
    val host = Uri.parse(url).host ?: return false
    return host.contains(".") && host.length >= 4
}

/** Accepts "90", "1:30" or "1:02:03". Returns seconds, or null if the text is not a valid time. */
private fun parseClockText(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(":")
    if (parts.size > 3) return null
    val nums = ArrayList<Int>()
    for (part in parts) {
        val n = part.trim().toIntOrNull() ?: return null
        if (n < 0) return null
        nums.add(n)
    }
    return when (nums.size) {
        1 -> nums[0]
        2 -> {
            if (nums[1] > 59) return null
            nums[0] * 60 + nums[1]
        }
        else -> {
            if (nums[1] > 59 || nums[2] > 59) return null
            nums[0] * 3600 + nums[1] * 60 + nums[2]
        }
    }
}

private class ClipCheck(val start: Int, val end: Int, val error: String?)

private fun validateClip(fromText: String, toText: String, durationSec: Int): ClipCheck {
    val fromEmpty = fromText.isBlank()
    val toEmpty = toText.isBlank()

    val start = if (fromEmpty) 0 else parseClockText(fromText)
    val end = if (toEmpty) {
        if (durationSec > 0) durationSec else null
    } else {
        parseClockText(toText)
    }

    if (start == null || (!toEmpty && end == null)) {
        return ClipCheck(0, 0, "صيغة الوقت غير صحيحة. اكتب مثلاً 1:30")
    }
    if (end == null) {
        return ClipCheck(start, 0, "اكتب وقت النهاية")
    }
    if (end <= start) {
        return ClipCheck(start, end, "وقت النهاية يجب أن يكون بعد وقت البداية")
    }
    if (durationSec > 0 && start >= durationSec) {
        return ClipCheck(start, end, "البداية بعد نهاية الملف (" + formatTime(durationSec) + ")")
    }
    if (durationSec > 0 && end > durationSec) {
        return ClipCheck(start, end, "النهاية تتجاوز مدة الملف (" + formatTime(durationSec) + ")")
    }
    return ClipCheck(start, end, null)
}

private fun qualityLabel(height: Int): String {
    return when {
        height >= 4320 -> height.toString() + "p (8K)"
        height >= 2160 -> height.toString() + "p (4K)"
        height >= 1440 -> height.toString() + "p (2K)"
        else -> height.toString() + "p"
    }
}

private fun defaultHeight(info: MediaInfo): Int {
    if (info.qualities.isEmpty()) return 0
    val upTo720 = info.qualities.firstOrNull { it.height <= 720 }
    return upTo720?.height ?: info.qualities.last().height
}

private fun estimateBytes(info: MediaInfo?, audioOnly: Boolean, height: Int, clip: ClipCheck?): Long {
    if (info == null) return 0L
    var base = if (audioOnly) {
        info.audioBytes
    } else {
        info.qualities.firstOrNull { it.height == height }?.bytes ?: 0L
    }
    if (clip != null && clip.error == null && info.durationSec > 0 && base > 0) {
        base = base * (clip.end - clip.start) / info.durationSec
    }
    return base
}

private fun inspectErrorText(e: Throwable?): String {
    val base = "تعذّر فحص الجودات. سيُستخدم أفضل خيار حتى 720p."
    val reason = e?.message?.lines()?.firstOrNull { it.isNotBlank() }
    return if (reason != null) base + "\n" + reason.take(160) else base
}

private sealed class InspectUi {
    object Idle : InspectUi()
    object Loading : InspectUi()
    class Ready(val info: MediaInfo) : InspectUi()
    class Failed(val message: String) : InspectUi()
}

// ============================ Control button colors ============================

private val ResumeGreen = Color(0xFF1F8A4C)
private val PauseRed = Color(0xFFFF8F98)
private val PauseRedText = Color(0xFF3A0008)
private val CancelRed = Color(0xFFB3142B)

@Composable
private fun resumeColors(): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = ResumeGreen, contentColor = Color.White)

@Composable
private fun pauseColors(): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = PauseRed, contentColor = PauseRedText)

@Composable
private fun cancelColors(): ButtonColors =
    ButtonDefaults.buttonColors(containerColor = CancelRed, contentColor = Color.White)

// ============================ Activity ============================

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        addOnNewIntentListener { newIntent -> handleIntent(newIntent) }

        setContent { AppRoot(sharedUrl) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        // The notification "Cancel" button opens the app and asks for confirmation here.
        if (i.getBooleanExtra(EXTRA_CONFIRM_CANCEL, false)) {
            i.removeExtra(EXTRA_CONFIRM_CANCEL)
            DownloadService.requestCancel(this)
        }
        handleShareIntent(i)
    }

    private fun handleShareIntent(i: Intent) {
        if (i.action != Intent.ACTION_SEND) return
        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val url = cleanUrl(text) ?: cleanUrl(i.dataString)
        if (url != null) {
            sharedUrl.value = url
        } else {
            DownloadState.status.value = "لم أجد رابطاً في النص المشترك."
        }
    }

    companion object {
        const val EXTRA_CONFIRM_CANCEL = "confirm_cancel"
    }
}

@Composable
fun AppRoot(sharedUrl: MutableState<String?>) {
    ContentSlayerTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainScreen(sharedUrl)
        }
    }
}

// ============================ Main screen ============================

@Composable
fun MainScreen(sharedUrl: MutableState<String?>) {
    val appContext = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val phase by DownloadState.phase.collectAsState()
    val progress by DownloadState.progress.collectAsState()
    val bytesText by DownloadState.bytesText.collectAsState()
    val status by DownloadState.status.collectAsState()
    val confirmCancel by DownloadState.confirmCancel.collectAsState()
    val busy = phase == Phase.Downloading || phase == Phase.Paused

    var url by rememberSaveable { mutableStateOf("") }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var clipEnabled by rememberSaveable { mutableStateOf(false) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var hasCookies by remember { mutableStateOf(Downloader.hasCookies(appContext)) }
    var poToken by remember { mutableStateOf(Downloader.getPoToken(appContext)) }
    var visitorData by remember { mutableStateOf(Downloader.getVisitorData(appContext)) }
    var inspect by remember { mutableStateOf<InspectUi>(InspectUi.Idle) }
    var inspectedLink by remember { mutableStateOf<String?>(null) }
    var selectedHeight by remember { mutableStateOf(0) }
    var pendingSpec by remember { mutableStateOf<DownloadSpec?>(null) }

    val locked = busy || working
    val link = cleanUrl(url)
    val info = (inspect as? InspectUi.Ready)?.info
    val clipCheck = if (clipEnabled) validateClip(fromText, toText, info?.durationSec ?: 0) else null
    val clipErrorText = if (clipCheck != null && clipCheck.error != null &&
        (fromText.isNotBlank() || toText.isNotBlank())
    ) {
        clipCheck.error
    } else {
        null
    }

    // A link arrived from the Share menu.
    val shared = sharedUrl.value
    LaunchedEffect(shared) {
        if (shared != null) {
            url = shared
            sharedUrl.value = null
            if (!DownloadState.phase.value.let { it == Phase.Downloading || it == Phase.Paused }) {
                DownloadState.status.value =
                    "تم استلام الرابط من المشاركة ✓ اختر فيديو أو صوت ثم اضغط ابدأ."
            }
        }
    }

    // Stop an unfinished inspection when the link changes or a download starts.
    DisposableEffect(link, busy) {
        onDispose { Downloader.cancelInspect() }
    }

    // Quietly inspect the link (about a second after it appears) to list the real qualities.
    LaunchedEffect(link, busy) {
        if (busy) {
            if (inspect is InspectUi.Loading) inspect = InspectUi.Idle
            return@LaunchedEffect
        }
        if (link == null || !isPlausibleUrl(link)) {
            inspect = InspectUi.Idle
            inspectedLink = null
            return@LaunchedEffect
        }
        if (link == inspectedLink && inspect is InspectUi.Ready) {
            return@LaunchedEffect
        }

        inspect = InspectUi.Idle
        delay(900)
        inspect = InspectUi.Loading

        val result = withContext(Dispatchers.IO) {
            runCatching { Downloader.inspect(appContext, link) }
        }
        val found = result.getOrNull()
        if (found != null) {
            inspect = InspectUi.Ready(found)
            inspectedLink = link
            selectedHeight = defaultHeight(found)
        } else {
            val error = result.exceptionOrNull()
            if (error is DownloadCancelledException) return@LaunchedEffect
            inspect = InspectUi.Failed(inspectErrorText(error))
            inspectedLink = null
        }
    }

    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = Downloader.importCookies(appContext, uri)
            hasCookies = Downloader.hasCookies(appContext)
            DownloadState.status.value =
                if (ok) "تم استيراد ملف الكوكيز ✓ جرّب التحميل الآن." else "تعذر قراءة ملف الكوكيز."
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val onStartClick: () -> Unit = {
        val target = cleanUrl(url)
        if (target == null) {
            DownloadState.status.value = "الرابط غير صالح. تأكد أنه يبدأ بـ http أو https."
        } else if (clipCheck != null && clipCheck.error != null) {
            DownloadState.status.value = "تحقق من وقت بداية ونهاية المقطع."
        } else {
            if (inspect is InspectUi.Loading) {
                Downloader.cancelInspect()
                inspect = InspectUi.Idle
            }
            val clipOk = clipCheck != null && clipCheck.error == null
            val estimate = estimateBytes(info, audioOnly, selectedHeight, clipCheck)
            pendingSpec = DownloadSpec(
                url = target,
                audioOnly = audioOnly,
                maxHeight = if (audioOnly) 0 else (if (selectedHeight > 0) selectedHeight else 720),
                clipStart = if (clipOk && clipCheck != null) clipCheck.start else -1,
                clipEnd = if (clipOk && clipCheck != null) clipCheck.end else -1,
                estimatedBytes = estimate,
                hasPreferred = info != null,
                preferredClient = info?.bestClient,
                preferredCookies = info?.bestUsedCookies ?: false
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                RepairPanel(
                    locked = locked,
                    hasCookies = hasCookies,
                    status = status,
                    poToken = poToken,
                    visitorData = visitorData,
                    onPoTokenChange = { poToken = it },
                    onVisitorDataChange = { visitorData = it },
                    onSaveAdvanced = {
                        Downloader.setPoToken(appContext, poToken)
                        Downloader.setVisitorData(appContext, visitorData)
                        DownloadState.status.value = "تم حفظ إعدادات يوتيوب المتقدمة ✓"
                    },
                    onUpdateEngine = {
                        working = true
                        DownloadState.status.value = "جارٍ تحديث محرك التحميل…"
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                try {
                                    Downloader.updateEngine(appContext)
                                } catch (e: Exception) {
                                    val reason = (e.message ?: "خطأ غير معروف")
                                        .lines()
                                        .lastOrNull { it.isNotBlank() }
                                        ?: "خطأ غير معروف"
                                    "فشل التحديث: " + reason.take(300)
                                }
                            }
                            DownloadState.status.value = message
                            working = false
                        }
                    },
                    onImportCookies = { cookiePicker.launch(arrayOf("*/*")) },
                    onDeleteCookies = {
                        Downloader.clearCookies(appContext)
                        hasCookies = false
                        DownloadState.status.value = "تم حذف ملف الكوكيز."
                    }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // App name: always top-left, even though the app layout is right-to-left.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Main controls: centered in the middle of the screen.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 480.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("رابط الفيديو أو الصوت") },
                            singleLine = true,
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedButton(
                            onClick = {
                                val pasted = clipboard.getText()?.text
                                if (pasted != null) {
                                    url = cleanUrl(pasted) ?: pasted
                                }
                            },
                            enabled = !locked
                        ) {
                            Text("لصق من الحافظة")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            FilterChip(
                                selected = !audioOnly,
                                onClick = { if (!locked) audioOnly = false },
                                label = { Text("فيديو") }
                            )
                            FilterChip(
                                selected = audioOnly,
                                onClick = { if (!locked) audioOnly = true },
                                label = { Text("صوت") }
                            )
                        }

                        if (!audioOnly) {
                            QualitySection(
                                state = inspect,
                                selected = selectedHeight,
                                enabled = !locked,
                                onSelect = { selectedHeight = it }
                            )
                        }

                        ClipSection(
                            enabled = !locked,
                            clipEnabled = clipEnabled,
                            onClipEnabledChange = { clipEnabled = it },
                            fromText = fromText,
                            toText = toText,
                            onFromChange = { fromText = it },
                            onToChange = { toText = it },
                            durationSec = info?.durationSec ?: 0,
                            errorText = clipErrorText
                        )

                        Button(
                            onClick = onStartClick,
                            enabled = !locked && url.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("ابدأ التحميل", style = MaterialTheme.typography.titleMedium)
                        }

                        if (busy) {
                            DownloadControls(
                                phase = phase,
                                progress = progress,
                                bytesText = bytesText,
                                onPause = { DownloadService.pause(appContext) },
                                onResume = { DownloadService.resume(appContext) },
                                onCancel = { DownloadService.requestCancel(appContext) }
                            )
                        }

                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Settings gear: always bottom-right.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { drawerState.open() } },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "أدوات الإصلاح"
                        )
                    }
                }
            }
        }
    }

    val spec = pendingSpec
    if (spec != null) {
        SizeConfirmDialog(
            spec = spec,
            onYes = {
                pendingSpec = null
                DownloadService.start(appContext, spec)
            },
            onNo = { pendingSpec = null }
        )
    }

    if (confirmCancel) {
        CancelConfirmDialog(
            onYes = { DownloadService.cancelYes(appContext) },
            onNo = { DownloadService.cancelNo(appContext) }
        )
    }
}

// ============================ Sections ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualitySection(
    state: InspectUi,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    when (state) {
        is InspectUi.Idle -> Unit

        is InspectUi.Loading -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "جارٍ فحص الجودات…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is InspectUi.Failed -> {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        is InspectUi.Ready -> {
            if (state.info.qualities.isEmpty()) {
                Text(
                    text = "لا توجد جودات فيديو متاحة لهذا الرابط.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "الجودة المتاحة",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.info.qualities.forEach { quality ->
                            val sizeText = if (quality.bytes > 0) " • " + formatBytes(quality.bytes) else ""
                            FilterChip(
                                selected = selected == quality.height,
                                enabled = enabled,
                                onClick = { onSelect(quality.height) },
                                label = { Text(qualityLabel(quality.height) + sizeText) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipSection(
    enabled: Boolean,
    clipEnabled: Boolean,
    onClipEnabledChange: (Boolean) -> Unit,
    fromText: String,
    toText: String,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    durationSec: Int,
    errorText: String?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = clipEnabled,
                onCheckedChange = onClipEnabledChange,
                enabled = enabled
            )
            Text(text = "تحميل جزء محدد من الملف")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = fromText,
                onValueChange = { v ->
                    if (v.all { it.isDigit() || it == ':' }) onFromChange(v)
                },
                label = { Text("من (د:ث)") },
                placeholder = { Text("0:00") },
                singleLine = true,
                enabled = enabled && clipEnabled,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toText,
                onValueChange = { v ->
                    if (v.all { it.isDigit() || it == ':' }) onToChange(v)
                },
                label = { Text("إلى (د:ث)") },
                placeholder = { Text("0:00") },
                singleLine = true,
                enabled = enabled && clipEnabled,
                modifier = Modifier.weight(1f)
            )
        }

        if (clipEnabled && durationSec > 0) {
            Text(
                text = "مدة الملف: " + formatTime(durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DownloadControls(
    phase: Phase,
    progress: Float,
    bytesText: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val paused = phase == Phase.Paused

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (progress > 0f || paused) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (bytesText.isNotEmpty()) {
            Text(
                text = "\u2066" + bytesText + "\u2069",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onPause,
                enabled = phase == Phase.Downloading,
                colors = pauseColors(),
                modifier = Modifier.weight(1f)
            ) {
                Text("إيقاف", maxLines = 1)
            }
            Button(
                onClick = onResume,
                enabled = paused,
                colors = resumeColors(),
                modifier = Modifier.weight(1f)
            ) {
                Text("استئناف", maxLines = 1)
            }
            Button(
                onClick = onCancel,
                enabled = true,
                colors = cancelColors(),
                modifier = Modifier.weight(1f)
            ) {
                Text("إلغاء", maxLines = 1)
            }
        }
    }
}

// ============================ Dialogs ============================

@Composable
private fun SizeConfirmDialog(spec: DownloadSpec, onYes: () -> Unit, onNo: () -> Unit) {
    val typeText = if (spec.audioOnly) {
        "صوت (m4a)"
    } else if (spec.maxHeight > 0) {
        "فيديو " + qualityLabel(spec.maxHeight)
    } else {
        "فيديو"
    }
    val sizeText = if (spec.estimatedBytes > 0) {
        "≈ " + formatBytes(spec.estimatedBytes)
    } else {
        "غير معروف"
    }

    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("تأكيد التحميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("النوع: " + typeText)
                Text("الحجم المتوقع: " + sizeText)
                if (spec.hasClip) {
                    Text(
                        "المقطع: من " + formatTime(spec.clipStart) + " إلى " + formatTime(spec.clipEnd)
                    )
                }
                Text(
                    text = if (spec.estimatedBytes > 0) {
                        "الحجم تقديري وقد يختلف قليلاً بعد الدمج."
                    } else {
                        "تعذّر معرفة الحجم مسبقاً."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("هل تريد بدء التحميل؟")
            }
        },
        confirmButton = {
            Button(onClick = onYes) {
                Text("نعم")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onNo) {
                Text("لا")
            }
        }
    )
}

@Composable
private fun CancelConfirmDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("إلغاء التحميل؟") },
        text = {
            Text("تم إيقاف التحميل مؤقتاً. لو ألغيته سيُحذف الجزء المحمّل. هل تريد الإلغاء نهائياً؟")
        },
        confirmButton = {
            Button(onClick = onYes, colors = cancelColors()) {
                Text("نعم")
            }
        },
        dismissButton = {
            Button(onClick = onNo, colors = resumeColors()) {
                Text("لا")
            }
        }
    )
}

// ============================ Side drawer: repair tools ============================

@Composable
private fun RepairPanel(
    locked: Boolean,
    hasCookies: Boolean,
    status: String,
    poToken: String,
    visitorData: String,
    onPoTokenChange: (String) -> Unit,
    onVisitorDataChange: (String) -> Unit,
    onSaveAdvanced: () -> Unit,
    onUpdateEngine: () -> Unit,
    onImportCookies: () -> Unit,
    onDeleteCookies: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "أدوات الإصلاح",
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedButton(
            onClick = onUpdateEngine,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("تحديث محرك التحميل")
        }

        OutlinedButton(
            onClick = onImportCookies,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasCookies) "استبدال ملف الكوكيز (موجود)" else "استيراد ملف كوكيز (cookies.txt)")
        }

        if (hasCookies) {
            OutlinedButton(
                onClick = onDeleteCookies,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("حذف ملف الكوكيز")
            }
        }

        HorizontalDivider()

        Text(
            text = "إعدادات يوتيوب المتقدمة (اختياري)",
            style = MaterialTheme.typography.titleSmall
        )

        OutlinedTextField(
            value = poToken,
            onValueChange = onPoTokenChange,
            label = { Text("PO Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = visitorData,
            onValueChange = onVisitorDataChange,
            label = { Text("Visitor Data") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onSaveAdvanced,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("حفظ")
        }

        Text(
            text = "الصيغة: web.gvs+TOKEN. الـ PO Token مرتبط غالباً بفيديو واحد وينتهي بسرعة، فالإدخال اليدوي محدود الفائدة.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
