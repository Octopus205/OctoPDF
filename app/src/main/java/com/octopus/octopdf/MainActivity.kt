package com.octopus.octopdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val android.content.Context.octoDataStore by preferencesDataStore(name = "octopdf_settings")
private val RECENT_KEY = stringPreferencesKey("recent_documents")
private val Ink = Color(0xFF101820)
private val Navy = Color(0xFF142B45)
private val Blue = Color(0xFF4C9BE8)
private val Panel = Color(0xFF1B2938)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OctoPdfApp(intent?.data) }
    }
}

private data class RecentDocument(val uri: Uri, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OctoPdfApp(incomingUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf(incomingUri) }
    var documentName by remember { mutableStateOf(incomingUri?.lastPathSegment?.substringAfterLast('/') ?: "") }
    var pageCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val recentRaw by context.octoDataStore.data.collectAsState(initial = emptyPreferences())
    val recentDocuments = remember(recentRaw) {
        recentRaw[RECENT_KEY].orEmpty().split("\n").filter { it.isNotBlank() }.mapNotNull { entry ->
            val split = entry.split("\t", limit = 2)
            if (split.size == 2) runCatching { RecentDocument(Uri.parse(split[0]), split[1]) }.getOrNull() else null
        }
    }

    fun rememberDocument(uri: Uri, name: String) {
        scope.launch {
            context.octoDataStore.edit { prefs ->
                val value = "${uri}\t${name}"
                val updated = (listOf(value) + prefs[RECENT_KEY].orEmpty().split("\n").filter { it.isNotBlank() && it.substringBefore("\t") != uri.toString() }).take(20)
                prefs[RECENT_KEY] = updated.joinToString("\n")
            }
        }
    }

    fun openDocument(uri: Uri, name: String) {
        selectedUri = uri
        documentName = name
        pageCount = 0
        openError = null
        rememberDocument(uri, name)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
            openDocument(uri, name)
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        loading = true
        openError = null
        try {
            pageCount = withContext(Dispatchers.IO) {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("تعذر فتح الملف")
                descriptor.use { PdfRenderer(it).use { renderer -> renderer.pageCount } }
            }
            listState.scrollToItem(0)
        } catch (e: Exception) {
            openError = e.message ?: "تعذر قراءة ملف PDF"
        } finally {
            loading = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, onPrimary = Color.White, background = Ink,
        surface = Panel, onSurface = Color(0xFFEAF1F8)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (selectedUri == null) "OctoPDF" else documentName, maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(if (selectedUri == null) "قارئ PDF بدون إنترنت" else "عارض المستند", fontSize = 11.sp, color = Color.LightGray)
                        }
                    },
                    navigationIcon = {
                        if (selectedUri != null) {
                            IconButton(onClick = { selectedUri = null; pageCount = 0 }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "فتح ملف", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
                )
            },
            containerColor = Ink
        ) { padding ->
            if (selectedUri == null) {
                HomeScreen(
                    recent = recentDocuments,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onOpen = { openDocument(it.uri, it.name) },
                    onPick = { picker.launch(arrayOf("application/pdf")) }
                )
            } else {
                ReaderScreen(
                    uri = selectedUri!!,
                    pageCount = pageCount,
                    loading = loading,
                    error = openError,
                    listState = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onClose = { selectedUri = null; pageCount = 0 }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    recent: List<RecentDocument>,
    modifier: Modifier = Modifier,
    onOpen: (RecentDocument) -> Unit,
    onPick: () -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Navy)
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.PictureAsPdf, null, tint = Blue, modifier = Modifier.size(44.dp))
                    Text("اقرأ ملفاتك بطريقتك", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("افتح مستندات PDF واقرأها بسهولة، مع تجربة مصممة للهواتف والتابلت.", color = Color(0xFFCBD8E6))
                    Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("فتح ملف PDF")
                    }
                }
            }
        }
        item { Text("المستندات الأخيرة", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        if (recent.isEmpty()) {
            item { Text("لا توجد ملفات حديثة بعد. افتح أول ملف PDF للبدء.", color = Color.LightGray) }
        } else {
            items(recent, key = { it.uri.toString() }) { doc ->
                Card(
                    onClick = { onOpen(doc) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = Blue, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(doc.name, modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.Medium)
                        Icon(Icons.Default.ArrowForward, null, tint = Color.LightGray)
                    }
                }
            }
        }
        item { Text("OctoPDF  •  الإصدار 0.2.0", color = Color.Gray, fontSize = 12.sp) }
    }
}

@Composable
private fun ReaderScreen(
    uri: Uri,
    pageCount: Int,
    loading: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val currentPage by remember { derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount.coerceAtLeast(1)) } }
    val scope = rememberCoroutineScope()
    Column(modifier.background(Ink)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (pageCount > 0) "صفحة $currentPage من $pageCount" else "جارٍ تجهيز المستند", fontWeight = FontWeight.SemiBold)
                Text("اسحب للتمرير • استخدم إصبعين للتكبير", fontSize = 11.sp, color = Color.LightGray)
            }
            IconButton(onClick = { onClose() }) { Icon(Icons.Default.Close, contentDescription = "إغلاق") }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            pageCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("المستند لا يحتوي على صفحات قابلة للعرض.")
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pageCount, key = { it }) { index ->
                        PdfPageItem(uri = uri, pageIndex = index)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Navy).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = currentPage > 1,
                        onClick = { scope.launch { listState.animateScrollToItem((currentPage - 2).coerceAtLeast(0)) } }
                    ) { Icon(Icons.Default.ArrowBack, contentDescription = "الصفحة السابقة") }
                    Text("$currentPage / $pageCount", fontWeight = FontWeight.SemiBold)
                    IconButton(
                        enabled = currentPage < pageCount,
                        onClick = { scope.launch { listState.animateScrollToItem((currentPage).coerceAtMost(pageCount - 1)) } }
                    ) { Icon(Icons.Default.ArrowForward, contentDescription = "الصفحة التالية") }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(uri: Uri, pageIndex: Int) {
    val context = LocalContext.current
    var bitmap by remember(uri, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri, pageIndex) { mutableStateOf(false) }
    var zoom by remember(uri, pageIndex) { mutableFloatStateOf(1f) }

    LaunchedEffect(uri, pageIndex) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open PDF")
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val width = 1200
                            val height = (page.height * width.toFloat() / page.width).toInt().coerceAtLeast(1)
                            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image
                        }
                    }
                }
            }
        } catch (_: Exception) { error = true }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "صفحة ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer(scaleX = zoom, scaleY = zoom)
                    .pointerInput(pageIndex) {
                        detectTransformGestures { _, _, scaleChange, _ ->
                            zoom = (zoom * scaleChange).coerceIn(1f, 4f)
                        }
                    }
            )
            error -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("تعذر تحميل الصفحة ${pageIndex + 1}", color = Color.DarkGray)
            }
            else -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
