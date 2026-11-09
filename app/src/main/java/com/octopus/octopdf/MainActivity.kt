package com.octopus.octopdf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.withContext

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
    data class Ready(val session: PdfSession) : PdfOpenState
    data class Failed(val message: String) : PdfOpenState
}

private data class PageRenderState(val bitmap: Bitmap? = null, val error: String? = null)

private class PdfSession private constructor(
    private val descriptor: android.os.ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageCount: Int,
    private val cachePrefix: String
) {
    private val lock = Any()
    private var closed = false

    fun renderPage(index: Int, targetWidth: Int): Bitmap {
        require(index in 0 until pageCount) { "رقم الصفحة غير صحيح" }
        val width = targetWidth.coerceIn(480, 1600)
        val key = "$cachePrefix:$index:$width"
        pageBitmapCache.get(key)?.let { return it }

        return synchronized(lock) {
            check(!closed) { "تم إغلاق المستند" }
            pageBitmapCache.get(key)?.let { return@synchronized it }
            renderer.openPage(index).use { page ->
                val height = (page.height.toFloat() * width / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageBitmapCache.put(key, bitmap)
                bitmap
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    companion object {
        private val pageBitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        fun open(context: Context, uri: Uri): PdfSession {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("لم يتمكن أندرويد من فتح الملف. جرّب اختيار الملف مرة أخرى.")
            try {
                val renderer = PdfRenderer(descriptor)
                return PdfSession(descriptor, renderer, renderer.pageCount, uri.toString())
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
        }
    }
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
        val uri = selectedUri
        if (uri == null) {
            openState = PdfOpenState.Idle
            return@LaunchedEffect
        }
        openState = PdfOpenState.Loading
        try {
            val session = withContext(Dispatchers.IO) { PdfSession.open(context, uri) }
            if (documentName.isBlank()) documentName = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
            openState = if (session.pageCount > 0) PdfOpenState.Ready(session) else {
                session.close()
                PdfOpenState.Failed("هذا الملف لا يحتوي على صفحات قابلة للعرض.")
            }
            listState.scrollToItem(0)
        } catch (error: Exception) {
            openState = PdfOpenState.Failed(
                error.message?.takeIf { it.isNotBlank() } ?: "تعذر فتح ملف PDF. تأكد أن الملف سليم وأن لديك صلاحية الوصول إليه."
            )
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

    DisposableEffect(openState) {
        val active = (openState as? PdfOpenState.Ready)?.session
        onDispose { active?.close() }
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
                    state = openState,
                    listState = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRetry = { openAttempt++ },
                    onClose = { selectedUri = null }
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
    state: PdfOpenState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val session = (state as? PdfOpenState.Ready)?.session
    val pageCount = session?.pageCount ?: 0
    val currentPage by remember { derivedStateOf { (listState.firstVisibleItemIndex + 1).coerceIn(1, pageCount.coerceAtLeast(1)) } }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetWidth = with(density) { (configuration.screenWidthDp.dp - 36.dp).roundToPx().coerceIn(480, 1600) }
    var readingMode by remember(session) { mutableStateOf(ReadingMode.LIGHT) }
    var settingsExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.background(Ink)) {
        val compactLandscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
        if (session != null) {
            Surface(color = Panel, tonalElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compactLandscape) 1.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("صفحة $currentPage من $pageCount", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (!compactLandscape) Text(name, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(enabled = currentPage > 1, onClick = { scope.launch { listState.animateScrollToItem(currentPage - 2) } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "الصفحة السابقة")
                    }
                    IconButton(enabled = currentPage < pageCount, onClick = { scope.launch { listState.animateScrollToItem(currentPage) } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "الصفحة التالية")
                    }
                    Box {
                        IconButton(onClick = { settingsExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "إعدادات القراءة")
                        }
                        DropdownMenu(expanded = settingsExpanded, onDismissRequest = { settingsExpanded = false }) {
                            DropdownMenuItem(text = { Text("الوضع الفاتح") }, onClick = { readingMode = ReadingMode.LIGHT; settingsExpanded = false })
                            DropdownMenuItem(text = { Text("الوضع Sepia") }, onClick = { readingMode = ReadingMode.SEPIA; settingsExpanded = false })
                            DropdownMenuItem(text = { Text("الوضع الليلي") }, onClick = { readingMode = ReadingMode.NIGHT; settingsExpanded = false })
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = if (compactLandscape) 6.dp else 10.dp, vertical = if (compactLandscape) 4.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactLandscape) 6.dp else 12.dp)
            ) {
                items(pageCount, key = { index -> session.hashCode().toString() + "-" + index }) { index ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val pageMaxWidth = if (maxWidth >= 700.dp) 980.dp else maxWidth
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            PdfPageItem(session, index, targetWidth, readingMode, Modifier.widthIn(max = pageMaxWidth))
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    PdfOpenState.Idle, PdfOpenState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = Accent)
                        Text("جارٍ فتح المستند وتجهيز الصفحات…", color = TextMuted)
                    }
                    is PdfOpenState.Failed -> Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = Accent, modifier = Modifier.size(48.dp))
                        Text("تعذر فتح المستند", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(state.message, color = TextMuted)
                        Button(onClick = onRetry) { Text("إعادة المحاولة") }
                        TextButton(onClick = onClose) { Text("العودة إلى الملفات") }
                    }
                    is PdfOpenState.Ready -> Unit
                }
            }
        }
        }
    }
}

@Composable
private fun PdfPageItem(
    session: PdfSession,
    pageIndex: Int,
    targetWidth: Int,
    readingMode: ReadingMode,
    modifier: Modifier = Modifier
) {
    var zoom by remember(session, pageIndex) { mutableFloatStateOf(1f) }
    val result by produceState(
        initialValue = PageRenderState(),
        key1 = session, key2 = pageIndex, key3 = targetWidth
    ) {
        value = try {
            PageRenderState(bitmap = withContext(Dispatchers.IO) { session.renderPage(pageIndex, targetWidth) })
        } catch (error: Exception) {
            PageRenderState(error = error.message ?: "تعذر رسم الصفحة")
        }
    }

    val pageColorFilter = when (readingMode) {
        ReadingMode.LIGHT -> null
        ReadingMode.SEPIA -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        )
        ReadingMode.NIGHT -> androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        )
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        when {
            result.bitmap != null -> Image(
                bitmap = result.bitmap!!.asImageBitmap(),
                contentDescription = "صفحة " + (pageIndex + 1),
                contentScale = ContentScale.FillWidth,
                colorFilter = pageColorFilter,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer(scaleX = zoom, scaleY = zoom)
                    .pointerInput(session, pageIndex) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.size >= 2) {
                                    val first = activePointers[0]
                                    val second = activePointers[1]
                                    val previousDistance = (first.previousPosition - second.previousPosition).getDistance()
                                    val currentDistance = (first.position - second.position).getDistance()
                                    if (previousDistance > 0f && currentDistance > 0f) {
                                        zoom = (zoom * (currentDistance / previousDistance)).coerceIn(1f, 4f)
                                    }
                                    activePointers.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            )
            result.error != null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("تعذر تحميل الصفحة " + (pageIndex + 1) + ": " + result.error, color = Color.DarkGray)
            }
            else -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        }
    }
}
