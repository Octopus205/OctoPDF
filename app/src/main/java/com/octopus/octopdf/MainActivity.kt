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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ViewColumn
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

private val Ink = Color(0xFF0A111B)
private val Navy = Color(0xFF111F2E)
private val Panel = Color(0xFF152535)
private val PanelLight = Color(0xFF20394A)
private val Accent = Color(0xFF28B9A8)
private val Cyan = Color(0xFF53E0CF)
private val TextPrimary = Color(0xFFF3F7FC)
private val TextMuted = Color(0xFFA9B9CA)

private enum class ReadingMode { LIGHT, NIGHT }

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

private enum class AppSection { READER, SPLIT, RECENT, ENGINE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OctoPdfApp(incomingUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf(incomingUri) }
    var documentName by remember { mutableStateOf(incomingUri?.let { queryDisplayName(context, it) }.orEmpty()) }
    var openState by remember { mutableStateOf<PdfOpenState>(PdfOpenState.Idle) }
    var openAttempt by remember { mutableIntStateOf(0) }
    var section by remember { mutableStateOf(if (incomingUri != null) AppSection.READER else AppSection.RECENT) }
    var secondaryUri by remember { mutableStateOf<Uri?>(null) }
    var secondaryName by remember { mutableStateOf("") }
    val openTabs = remember { mutableStateListOf<RecentDocument>() }

    val recentRaw by context.octoDataStore.data.collectAsState(initial = emptyPreferences())
    val recentDocuments = remember(recentRaw) {
        recentRaw[RECENT_KEY].orEmpty().lineSequence().mapNotNull(::decodeRecent).toList()
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null && openTabs.none { it.uri == incomingUri }) {
            openTabs.add(RecentDocument(incomingUri, queryDisplayName(context, incomingUri)))
        }
    }

    fun rememberDocument(uri: Uri, name: String) {
        scope.launch {
            context.octoDataStore.edit { prefs ->
                val value = encodeRecent(uri, name)
                val updated = (listOf(value) + prefs[RECENT_KEY].orEmpty().lineSequence()
                    .filter { it.isNotBlank() && it.substringBefore("\\t") != uri.toString() }.toList()).take(30)
                prefs[RECENT_KEY] = updated.joinToString("\\n")
            }
        }
    }

    fun openDocument(uri: Uri, name: String, secondary: Boolean = false) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (secondary) {
            secondaryUri = uri
            secondaryName = name
        } else {
            selectedUri = uri
            documentName = name
            openAttempt++
            section = AppSection.READER
        }
        if (openTabs.none { it.uri == uri }) openTabs.add(0, RecentDocument(uri, name))
        rememberDocument(uri, name)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openDocument(uri, queryDisplayName(context, uri))
    }
    val secondaryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openDocument(uri, queryDisplayName(context, uri), secondary = true)
    }

    LaunchedEffect(selectedUri, openAttempt) {
        openState = if (selectedUri == null) PdfOpenState.Idle else PdfOpenState.Loading
    }

    val activity = context as? ComponentActivity
    DisposableEffect(selectedUri, activity) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val tablet = LocalConfiguration.current.screenWidthDp >= 600
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Cyan, background = Ink, surface = Panel,
            surfaceVariant = PanelLight, onPrimary = Color.White, onBackground = TextPrimary,
            onSurface = TextPrimary, onSurfaceVariant = TextMuted
        )
    ) {
        Row(Modifier.fillMaxSize().background(Ink)) {
            if (tablet) {
                NavigationRail(
                    containerColor = Color(0xFF0D1724),
                    contentColor = TextMuted,
                    header = {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.octopdf_icon),
                            contentDescription = "OctoPDF",
                            modifier = Modifier.padding(vertical = 18.dp).size(42.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    }
                ) {
                    RailDestination("القارئ", Icons.Default.MenuBook, section == AppSection.READER) { section = AppSection.READER }
                    RailDestination("تقسيم الشاشة", Icons.Default.ViewColumn, section == AppSection.SPLIT) { section = AppSection.SPLIT }
                    RailDestination("الأخيرة", Icons.Default.History, section == AppSection.RECENT) { section = AppSection.RECENT }
                    RailDestination("محرك PDF", Icons.Default.Memory, section == AppSection.ENGINE) { section = AppSection.ENGINE }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Surface(color = Color(0xFF101D2B), tonalElevation = 2.dp) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!tablet) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.octopdf_icon),
                                    contentDescription = "OctoPDF",
                                    modifier = Modifier.size(34.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("OctoPDF", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(
                                    when (section) {
                                        AppSection.READER -> "مساحة القراءة"
                                        AppSection.SPLIT -> "عرض مستندين"
                                        AppSection.RECENT -> "ملفاتك ومستنداتك"
                                        AppSection.ENGINE -> "تقنيات التطبيق"
                                    },
                                    fontSize = 10.sp, color = TextMuted
                                )
                            }
                            Surface(color = Color(0xFF123A3A), shape = RoundedCornerShape(50)) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Cyan))
                                    Text("يعمل دون إنترنت", color = Color(0xFF8DE8D8), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            IconButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "فتح ملف", tint = Accent)
                            }
                        }
                        if ((section == AppSection.READER || section == AppSection.SPLIT) && openTabs.isNotEmpty()) {
                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier.fillMaxWidth().height(44.dp)
                                    .background(Color(0xFF0B1420)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(openTabs, key = { it.uri.toString() }) { tab ->
                                    val active = tab.uri == selectedUri
                                    Surface(
                                        onClick = {
                                            selectedUri = tab.uri
                                            documentName = tab.name
                                            section = AppSection.READER
                                            openAttempt++
                                        },
                                        color = if (active) Color(0xFF173A4A) else Color(0xFF142333),
                                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                                        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.45f)) else null
                                    ) {
                                        Row(
                                            Modifier.padding(start = 11.dp, end = 5.dp).fillMaxHeight(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, null, tint = if (active) Cyan else TextMuted, modifier = Modifier.size(15.dp))
                                            Text(tab.name, maxWidth = 145.dp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                                            IconButton(
                                                onClick = {
                                                    openTabs.remove(tab)
                                                    if (selectedUri == tab.uri) {
                                                        val next = openTabs.firstOrNull()
                                                        selectedUri = next?.uri
                                                        documentName = next?.name.orEmpty()
                                                        openAttempt++
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "إغلاق التبويب", modifier = Modifier.size(13.dp), tint = TextMuted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        AppSection.READER -> {
                            if (selectedUri == null) {
                                HomeScreen(
                                    recent = recentDocuments,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpen = { openDocument(it.uri, it.name) },
                                    onPick = { picker.launch(arrayOf("application/pdf")) }
                                )
                            } else {
                                ReaderScreen(
                                    name = documentName, uri = selectedUri!!, state = openState,
                                    reloadKey = openAttempt, modifier = Modifier.fillMaxSize(),
                                    onRetry = { openAttempt++ }, onClose = { selectedUri = null; section = AppSection.RECENT },
                                    onLoaded = { openState = PdfOpenState.Ready },
                                    onFailed = { openState = PdfOpenState.Failed(it) }
                                )
                            }
                        }
                        AppSection.RECENT -> HomeScreen(
                            recent = recentDocuments, modifier = Modifier.fillMaxSize(),
                            onOpen = { openDocument(it.uri, it.name) },
                            onPick = { picker.launch(arrayOf("application/pdf")) }
                        )
                        AppSection.SPLIT -> {
                            val left = selectedUri
                            val right = secondaryUri
                            if (left != null && right != null) {
                                if (tablet) {
                                    Row(Modifier.fillMaxSize()) {
                                        ReaderScreen(
                                            name = documentName, uri = left, state = PdfOpenState.Ready,
                                            reloadKey = openAttempt, modifier = Modifier.weight(1f).fillMaxHeight(),
                                            onRetry = {}, onClose = { section = AppSection.READER }, onLoaded = {}, onFailed = {}
                                        )
                                        androidx.compose.material3.VerticalDivider(color = Color(0xFF294052), thickness = 1.dp)
                                        ReaderScreen(
                                            name = secondaryName, uri = right, state = PdfOpenState.Ready,
                                            reloadKey = 0, modifier = Modifier.weight(1f).fillMaxHeight(),
                                            onRetry = {}, onClose = { section = AppSection.READER }, onLoaded = {}, onFailed = {}
                                        )
                                    }
                                } else {
                                    Column(Modifier.fillMaxSize()) {
                                        ReaderScreen(
                                            name = documentName, uri = left, state = PdfOpenState.Ready,
                                            reloadKey = openAttempt, modifier = Modifier.weight(1f).fillMaxWidth(),
                                            onRetry = {}, onClose = { section = AppSection.READER }, onLoaded = {}, onFailed = {}
                                        )
                                        androidx.compose.material3.HorizontalDivider(color = Color(0xFF294052), thickness = 1.dp)
                                        ReaderScreen(
                                            name = secondaryName, uri = right, state = PdfOpenState.Ready,
                                            reloadKey = 0, modifier = Modifier.weight(1f).fillMaxWidth(),
                                            onRetry = {}, onClose = { section = AppSection.READER }, onLoaded = {}, onFailed = {}
                                        )
                                    }
                                }
                            } else {
                                SplitSetupScreen(
                                    primaryName = documentName.ifBlank { "المستند الأول" },
                                    hasPrimary = left != null,
                                    onPickPrimary = { picker.launch(arrayOf("application/pdf")) },
                                    onPickSecondary = { secondaryPicker.launch(arrayOf("application/pdf")) }
                                )
                            }
                        }
                        AppSection.ENGINE -> EngineInfoScreen()
                    }
                }

                if (!tablet) {
                    NavigationBar(containerColor = Color(0xFF0D1724), contentColor = TextMuted) {
                        BottomDestination("القارئ", Icons.Default.MenuBook, section == AppSection.READER) { section = AppSection.READER }
                        BottomDestination("تقسيم", Icons.Default.ViewColumn, section == AppSection.SPLIT) { section = AppSection.SPLIT }
                        BottomDestination("الأخيرة", Icons.Default.History, section == AppSection.RECENT) { section = AppSection.RECENT }
                        BottomDestination("المحرك", Icons.Default.Memory, section == AppSection.ENGINE) { section = AppSection.ENGINE }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailDestination(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected, onClick = onClick, icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 9.sp) }, alwaysShowLabel = false,
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = Cyan, selectedTextColor = Cyan,
            indicatorColor = Color(0xFF173A4A), unselectedIconColor = TextMuted
        )
    )
}

@Composable
private fun BottomDestination(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected, onClick = onClick, icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 10.sp) }, alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Cyan, selectedTextColor = Cyan,
            indicatorColor = Color(0xFF173A4A), unselectedIconColor = TextMuted
        )
    )
}

@Composable
private fun SplitSetupScreen(
    primaryName: String,
    hasPrimary: Boolean,
    onPickPrimary: () -> Unit,
    onPickSecondary: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Ink).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ViewColumn, null, tint = Cyan, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("مقارنة مستندين", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("افتح ملفين لعرضهما جنبًا إلى جنب ومقارنة المحتوى.", color = TextMuted)
        Spacer(Modifier.height(22.dp))
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (hasPrimary) "المستند الأول: $primaryName" else "المستند الأول غير محدد", fontSize = 13.sp)
                OutlinedButton(onClick = onPickPrimary, modifier = Modifier.fillMaxWidth()) { Text("اختيار المستند الأول") }
                Button(onClick = onPickSecondary, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("اختيار المستند الثاني")
                }
            }
        }
    }
}

@Composable
private fun EngineInfoScreen() {
    LazyColumn(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("محرك OctoPDF", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("البنية التقنية التي تجعل القراءة والمعالجة محلية على جهازك.", color = TextMuted)
        }
        item {
            EngineCard("PDFium", "محرك العرض الأساسي", "يعرض صفحات PDF الحقيقية مع التمرير الرأسي والتكبير باللمس. تم اختياره للأداء على أجهزة ARM.")
            EngineCard("Android Storage Access Framework", "فتح الملفات بأمان", "يتيح اختيار ملفات PDF من ذاكرة الجهاز أو موفري الملفات مع الاحتفاظ بصلاحية الوصول.")
            EngineCard("Jetpack Compose", "واجهة تكيفية", "واجهة أصلية تتكيف مع الهاتف والتابلت، وتدعم العربية واتجاه RTL.")
            EngineCard("Offline first", "خصوصية محلية", "لا يحتاج التطبيق إلى اتصال بالإنترنت لفتح المستندات وقراءتها.")
        }
        item {
            Text("ملاحظة: هذه صفحة معلومات عن مكونات التطبيق، وليست شاشة لمراقبة استهلاك CPU.", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EngineCard(title: String, subtitle: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Cyan, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(body, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences by context.octoDataStore.data.collectAsState(initial = emptyPreferences())
    val documentToken = remember(uri) {
        Base64.encodeToString(uri.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    }
    val lastPageKey = remember(documentToken) { stringPreferencesKey("last_page_$documentToken") }
    val bookmarksKey = remember(documentToken) { stringPreferencesKey("bookmarks_$documentToken") }
    val resumePage = preferences[lastPageKey]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val bookmarks = remember(preferences[bookmarksKey]) {
        preferences[bookmarksKey].orEmpty().split(',').mapNotNull { it.toIntOrNull() }
            .filter { it >= 0 }.distinct().sorted()
    }
    var pageNumber by remember(uri) { mutableIntStateOf(resumePage + 1) }
    var totalPages by remember(uri) { mutableIntStateOf(0) }
    var readingMode by remember(uri) { mutableStateOf(ReadingMode.LIGHT) }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pdfView by remember(uri) { mutableStateOf<PDFView?>(null) }

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
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (pageNumber - 1 in bookmarks) "إزالة علامة الصفحة الحالية" else "إضافة علامة مرجعية للصفحة الحالية") },
                                onClick = {
                                    val pageIndex = (pageNumber - 1).coerceAtLeast(0)
                                    val updated = if (pageIndex in bookmarks) bookmarks - pageIndex else (bookmarks + pageIndex).sorted()
                                    scope.launch {
                                        context.octoDataStore.edit { prefs ->
                                            prefs[bookmarksKey] = updated.joinToString(",")
                                        }
                                    }
                                    menuExpanded = false
                                }
                            )
                            if (bookmarks.isNotEmpty()) {
                                HorizontalDivider()
                                bookmarks.forEach { pageIndex ->
                                    DropdownMenuItem(
                                        text = { Text("الانتقال إلى العلامة: صفحة ${pageIndex + 1}") },
                                        onClick = {
                                            pdfView?.jumpTo(pageIndex, true)
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
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
                            pdfView = this
                            setBackgroundColor(
                                if (readingMode == ReadingMode.NIGHT) android.graphics.Color.rgb(12, 18, 26)
                                else android.graphics.Color.rgb(32, 43, 55)
                            )
                            fromUri(uri)
                                .defaultPage(resumePage)
                                .enableSwipe(true)
                                .swipeHorizontal(false)
                                .enableDoubleTap(true)
                                // Favor responsive scrolling on low/mid-range devices.
                                // RGB_565 remains the library default to reduce bitmap memory.
                                .enableAntialiasing(false)
                                .autoSpacing(false)
                                .pageSnap(false)
                                .pageFling(false)
                                .fitEachPage(false)
                                .enableAnnotationRendering(false)
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
                                        scope.launch {
                                            context.octoDataStore.edit { prefs ->
                                                prefs[lastPageKey] = page.toString()
                                            }
                                        }
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

