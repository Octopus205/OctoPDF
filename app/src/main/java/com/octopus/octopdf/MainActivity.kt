package com.octopus.octopdf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import com.ahmer.pdfviewer.PDFView
import com.ahmer.pdfviewer.util.FitPolicy

private val Context.octoDataStore by preferencesDataStore(name = "octopdf_settings")
private val RECENT_KEY = stringPreferencesKey("recent_documents")

private val Ink = Color(0xFF0B1420)
private val Navy = Color(0xFF102A46)
private val Panel = Color(0xFF172B40)
private val PanelLight = Color(0xFF203A54)
private val Accent = Color(0xFF39A9F5)
private val Cyan = Color(0xFF28D4E8)
private val TextPrimary = Color(0xFFF3F7FC)
private val TextMuted = Color(0xFFA9B9CA)

private enum class ReadingMode { LIGHT, SEPIA, NIGHT }

private data class RecentDocument(val uri: Uri, val name: String)

private sealed interface PdfOpenState {
    data object Idle : PdfOpenState
    data object Loading : PdfOpenState
    data object Ready : PdfOpenState
    data class Failed(val message: String) : PdfOpenState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent?.data
        if (incoming != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    incoming,
                    intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                )
            }
        }
        setContent { OctoPdfApp(incoming) }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } ?: "مستند PDF" else "مستند PDF"
        } else {
            uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "مستند PDF"
        }
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "مستند PDF"
    } finally {
        cursor?.close()
    }
}

private fun encodeRecent(uri: Uri, name: String): String {
    val encodedName = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    return uri.toString() + "\t" + encodedName
}

private fun decodeRecent(raw: String): RecentDocument? {
    val split = raw.split("\t", limit = 2)
    if (split.size != 2) return null
    return runCatching {
        RecentDocument(Uri.parse(split[0]), String(Base64.decode(split[1], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8))
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OctoPdfApp(incomingUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf(incomingUri) }
    var documentName by remember { mutableStateOf(incomingUri?.let { queryDisplayName(context, it) }.orEmpty()) }
    var openState by remember { mutableStateOf<PdfOpenState>(PdfOpenState.Idle) }
    var openAttempt by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val recentRaw by context.octoDataStore.data.collectAsState(initial = emptyPreferences())
    val recentDocuments = remember(recentRaw) {
        recentRaw[RECENT_KEY].orEmpty().lineSequence().mapNotNull(::decodeRecent).toList()
    }

    fun rememberDocument(uri: Uri, name: String) {
        scope.launch {
            context.octoDataStore.edit { prefs ->
                val value = encodeRecent(uri, name)
                val updated = (listOf(value) + prefs[RECENT_KEY].orEmpty().lineSequence()
                    .filter { it.isNotBlank() && it.substringBefore("\t") != uri.toString() }.toList()).take(30)
                prefs[RECENT_KEY] = updated.joinToString("\n")
            }
        }
    }

    fun openDocument(uri: Uri, name: String) {
        selectedUri = uri
        documentName = name
        openAttempt++
        rememberDocument(uri, name)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            openDocument(uri, queryDisplayName(context, uri))
        }
    }

    LaunchedEffect(selectedUri, openAttempt) {
        if (selectedUri == null) {
            openState = PdfOpenState.Idle
        } else {
            openState = PdfOpenState.Loading
        }
    }

    val activity = context as? ComponentActivity
    DisposableEffect(selectedUri, activity) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (selectedUri != null) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Cyan, background = Ink, surface = Panel,
            surfaceVariant = PanelLight, onPrimary = Color.White, onBackground = TextPrimary,
            onSurface = TextPrimary, onSurfaceVariant = TextMuted
        )
    ) {
        Scaffold(
            containerColor = Ink,
            topBar = {
                if (selectedUri == null) TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                if (selectedUri == null) "OctoPDF" else documentName,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold, fontSize = 19.sp
                            )
                            Text(
                                if (selectedUri == null) "مساحة ملفات PDF الخاصة بك" else "عارض المستند",
                                color = TextMuted, fontSize = 11.sp
                            )
                        }
                    },
                    navigationIcon = {
                        if (selectedUri != null) IconButton(onClick = { selectedUri = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    },
                    actions = {
                        IconButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "فتح ملف PDF")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
                )
            }
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
                    name = documentName,
                    uri = selectedUri!!,
                    state = openState,
                    reloadKey = openAttempt,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRetry = { openAttempt++ },
                    onClose = { selectedUri = null },
                    onLoaded = { openState = PdfOpenState.Ready },
                    onFailed = { openState = PdfOpenState.Failed(it) }
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
    BoxWithConstraints(modifier.background(Ink)) {
        val tablet = maxWidth >= 600.dp
        val contentWidth = if (tablet) 720.dp else maxWidth
        LazyColumn(
            modifier = Modifier.widthIn(max = contentWidth).align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = if (tablet) 28.dp else 18.dp),
            contentPadding = PaddingValues(top = 22.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Navy),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(Modifier.padding(if (tablet) 30.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(PanelLight), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Accent, modifier = Modifier.size(30.dp))
                            }
                            Column {
                                Text("مرحباً بك في OctoPDF", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text("قارئ PDF يعمل بدون إنترنت", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                        Text("ملفاتك، مرتبة. وقراءتك، أسهل.", fontSize = if (tablet) 28.sp else 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "افتح مستنداتك واقرأها في مساحة هادئة، مع الاحتفاظ بملفاتك الأخيرة على جهازك.",
                            color = Color(0xFFD0DCE8), lineHeight = 23.sp
                        )
                        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(10.dp))
                            Text("اختيار ملف PDF", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("المستندات الأخيرة", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(recent.size.toString() + " مستند محفوظ في القائمة", color = TextMuted, fontSize = 12.sp)
                    }
                    Surface(color = Panel, shape = CircleShape) {
                        Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.padding(10.dp).size(20.dp))
                    }
                }
            }
            if (recent.isEmpty()) {
                item {
                    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Book, null, tint = TextMuted, modifier = Modifier.size(36.dp))
                            Text("لا توجد مستندات بعد", fontWeight = FontWeight.SemiBold)
                            Text("اختر ملف PDF من جهازك وسيظهر هنا تلقائياً.", color = TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                items(recent, key = { it.uri.toString() }) { doc ->
                    Card(
                        onClick = { onOpen(doc) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(PanelLight), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Accent, modifier = Modifier.size(27.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("مستند PDF", color = TextMuted, fontSize = 11.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Accent)
                        }
                    }
                }
            }
            item {
                Text("OctoPDF  •  قارئ ومحرر PDF", color = TextMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    name: String,
    uri: Uri,
    state: PdfOpenState,
    modifier: Modifier = Modifier,
    reloadKey: Int,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onLoaded: () -> Unit,
    onFailed: (String) -> Unit
) {
    var pageNumber by remember(uri) { mutableIntStateOf(1) }
    var totalPages by remember(uri) { mutableIntStateOf(0) }
    var readingMode by remember(uri) { mutableStateOf(ReadingMode.LIGHT) }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier.background(Ink)) {
        if (controlsVisible) {
            Surface(color = Panel, tonalElevation = 1.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع للملفات")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (totalPages > 0) "صفحة $pageNumber من $totalPages" else "جارٍ تحميل المستند",
                            color = TextMuted, fontSize = 11.sp
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "إعدادات القراءة")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("الوضع الفاتح") }, onClick = { readingMode = ReadingMode.LIGHT; menuExpanded = false })
                            DropdownMenuItem(text = { Text("الوضع الليلي") }, onClick = { readingMode = ReadingMode.NIGHT; menuExpanded = false })
                            DropdownMenuItem(text = { Text("الوضع Sepia") }, onClick = { readingMode = ReadingMode.SEPIA; menuExpanded = false })
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(uri, readingMode, reloadKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PDFView(viewContext, null).apply {
                            setBackgroundColor(
                                when (readingMode) {
                                    ReadingMode.LIGHT -> android.graphics.Color.rgb(32, 43, 55)
                                    ReadingMode.NIGHT -> android.graphics.Color.rgb(12, 18, 26)
                                    ReadingMode.SEPIA -> android.graphics.Color.rgb(85, 71, 53)
                                }
                            )
                            fromUri(uri)
                                .enableSwipe(true)
                                .swipeHorizontal(false)
                                .enableDoubleTap(true)
                                .enableAntialiasing(true)
                                .autoSpacing(true)
                                .pageSnap(false)
                                .pageFling(false)
                                .fitEachPage(true)
                                .pageFitPolicy(FitPolicy.WIDTH)
                                .nightMode(readingMode == ReadingMode.NIGHT)
                                .onLoad(object : com.ahmer.pdfviewer.listener.OnLoadCompleteListener {
                                    override fun loadComplete(pages: Int) {
                                        totalPages = pages
                                        onLoaded()
                                    }
                                })
                                .onPageChange(object : com.ahmer.pdfviewer.listener.OnPageChangeListener {
                                    override fun onPageChanged(page: Int, pages: Int) {
                                        pageNumber = page + 1
                                        totalPages = pages
                                    }
                                })
                                .onError(object : com.ahmer.pdfviewer.listener.OnErrorListener {
                                    override fun onError(error: Throwable?) {
                                        onFailed(error?.message ?: "تعذر قراءة ملف PDF")
                                    }
                                })
                                .onTap(object : com.ahmer.pdfviewer.listener.OnTapListener {
                                    override fun onTap(event: android.view.MotionEvent?): Boolean {
                                        controlsVisible = !controlsVisible
                                        return true
                                    }
                                })
                                .load()
                        }
                    },
                    update = { }
                )
            }

            if (state is PdfOpenState.Loading) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = Panel,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                        Text("جارٍ فتح ملف PDF…")
                    }
                }
            }
            if (state is PdfOpenState.Failed) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("تعذر فتح المستند", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(state.message, color = TextMuted)
                    Button(onClick = onRetry) { Text("إعادة المحاولة") }
                    TextButton(onClick = onClose) { Text("العودة إلى الملفات") }
                }
            }
        }
    }
}

