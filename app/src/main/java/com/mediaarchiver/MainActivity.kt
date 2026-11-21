package com.mediaarchiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    val busy by DownloadState.busy.collectAsState()
    val progress by DownloadState.progress.collectAsState()
    val status by DownloadState.status.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var hasCookies by remember { mutableStateOf(Downloader.hasCookies(appContext)) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    DownloadState.status.value = "الرابط غير صالح. تأكد أنه يبدأ بـ http أو https."
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
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "أدوات الإصلاح",
            style = MaterialTheme.typography.titleSmall
        )

        OutlinedButton(
            onClick = {
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
            enabled = !locked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("تحديث محرك التحميل")
        }

        OutlinedButton(
            onClick = { cookiePicker.launch(arrayOf("*/*")) },
            enabled = !locked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasCookies) "استبدال ملف الكوكيز (موجود)" else "استيراد ملف كوكيز (cookies.txt)")
        }

        if (hasCookies) {
            OutlinedButton(
                onClick = {
                    Downloader.clearCookies(appContext)
                    hasCookies = false
                    DownloadState.status.value = "تم حذف ملف الكوكيز."
                },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("حذف ملف الكوكيز")
            }
        }
    }
}
