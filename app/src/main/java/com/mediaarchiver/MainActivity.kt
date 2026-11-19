package com.mediaarchiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val appContext = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf("") }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("جاهز. الصق الرابط ثم اضغط ابدأ.") }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "أرشيف الوسائط",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("رابط الفيديو أو الصوت") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                val pasted = clipboard.getText()?.text
                if (pasted != null) {
                    val found = Regex("https?://\\S+").find(pasted)
                    url = if (found != null) found.value else pasted
                }
            },
            enabled = !busy
        ) {
            Text("لصق من الحافظة")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !audioOnly,
                onClick = { if (!busy) audioOnly = false },
                label = { Text("فيديو") }
            )
            FilterChip(
                selected = audioOnly,
                onClick = { if (!busy) audioOnly = true },
                label = { Text("صوت") }
            )
        }

        Button(
            onClick = {
                val link = url.trim()
                busy = true
                progress = 0f
                status = "جارٍ التحميل… أبقِ التطبيق مفتوحاً."
                scope.launch {
                    val message = withContext(Dispatchers.IO) {
                        try {
                            val saved = Downloader.download(appContext, link, audioOnly) { p ->
                                progress = p.coerceIn(0f, 100f) / 100f
                            }
                            "تم الحفظ ✓\n" + saved
                        } catch (e: Exception) {
                            val reason = (e.message ?: "خطأ غير معروف")
                                .lines()
                                .lastOrNull { it.isNotBlank() }
                                ?: "خطأ غير معروف"
                            "فشل: " + reason.take(300)
                        }
                    }
                    status = message
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ابدأ التحميل")
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
            OutlinedButton(onClick = { Downloader.cancel() }) {
                Text("إلغاء")
            }
        }

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
