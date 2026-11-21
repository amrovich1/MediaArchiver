package com.mediaarchiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        addOnNewIntentListener { newIntent -> handleShareIntent(newIntent) }

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

    private fun handleShareIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val url = cleanUrl(text) ?: cleanUrl(i.dataString)
        if (url != null) {
            sharedUrl.value = url
        } else {
            DownloadState.status.value = "لم أجد رابطاً في النص المشترك."
        }
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

@Composable
fun MainScreen(sharedUrl: MutableState<String?>) {
    val appContext = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val busy by DownloadState.busy.collectAsState()
    val progress by DownloadState.progress.collectAsState()
    val status by DownloadState.status.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var hasCookies by remember { mutableStateOf(Downloader.hasCookies(appContext)) }
    var poToken by remember { mutableStateOf(Downloader.getPoToken(appContext)) }
    var visitorData by remember { mutableStateOf(Downloader.getVisitorData(appContext)) }

    val locked = busy || working

    // A link arrived from the Share menu.
    val shared = sharedUrl.value
    LaunchedEffect(shared) {
        if (shared != null) {
            url = shared
            sharedUrl.value = null
            if (!DownloadState.busy.value) {
                DownloadState.status.value =
                    "تم استلام الرابط من المشاركة ✓ اختر فيديو أو صوت ثم اضغط ابدأ."
            }
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

                        Button(
                            onClick = {
                                val link = cleanUrl(url)
                                if (link == null) {
                                    DownloadState.status.value =
                                        "الرابط غير صالح. تأكد أنه يبدأ بـ http أو https."
                                } else {
                                    url = link
                                    DownloadState.markStarting()
                                    DownloadService.start(appContext, link, audioOnly)
                                }
                            },
                            enabled = !locked && url.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("ابدأ التحميل", style = MaterialTheme.typography.titleMedium)
                        }

                        if (busy) {
                            if (progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            OutlinedButton(onClick = { DownloadService.cancel(appContext) }) {
                                Text("إلغاء")
                            }
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
}

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
