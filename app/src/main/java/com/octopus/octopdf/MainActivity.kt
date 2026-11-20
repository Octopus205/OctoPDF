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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import androidx.compose.ui.viewinterop.AndroidView
import com.ahmer.pdfviewer.PDFView
import com.ahmer.pdfviewer.util.FitPolicy

private val Context.octoDataStore by preferencesDataStore(name = "octopdf_settings")
private val RECENT_KEY = stringPreferencesKey("recent_documents")
private val FAVORITES_KEY = stringPreferencesKey("favorite_documents")
private val OPEN_TABS_KEY = stringPreferencesKey("open_tabs")
private val ACTIVE_TAB_KEY = stringPreferencesKey("active_tab")

// OctoPDF graphite + electric-violet design system.
private val Ink = Color(0xFF0B0D12)
private val Navy = Color(0xFF11151F)
private val Panel = Color(0xFF171C27)
private val PanelLight = Color(0xFF252D3D)
private val Accent = Color(0xFF8B7CFF)
private val Cyan = Color(0xFF66C8FF)
private val TextPrimary = Color(0xFFF5F6FC)
private val TextMuted = Color(0xFF9AA5B8)

private enum class ReadingMode { LIGHT, NIGHT }

private data class RecentDocument(val uri: Uri, val name: String)
private data class PdfSearchHit(val pageIndex: Int, val snippet: String, val matchCount: Int)

private suspend fun searchPdfDocument(context: Context, uri: Uri, query: String): List<PdfSearchHit> =
    withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "اكتب كلمة أو عبارة للبحث." }
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("تعذر فتح المستند للبحث.")
        input.use { stream ->
            PDDocument.load(stream).use { document ->
                val hits = mutableListOf<PdfSearchHit>()
                val matcher = Regex(Regex.escape(normalizedQuery), RegexOption.IGNORE_CASE)
                for (pageIndex in 0 until document.numberOfPages) {
                    val stripper = PDFTextStripper().apply {
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                        sortByPosition = true
                    }
                    val pageText = stripper.getText(document)
                    val matches = matcher.findAll(pageText).toList()
                    if (matches.isNotEmpty()) {
                        val first = matches.first()
                        val from = (first.range.first - 48).coerceAtLeast(0)
                        val to = (first.range.last + 81).coerceAtMost(pageText.length)
                        val snippet = pageText.substring(from, to)
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        hits += PdfSearchHit(pageIndex, snippet, matches.size)
                    }
                }
                hits
            }
        }
    }

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

private enum class AppSection { READER, SPLIT, RECENT, TOOLS, SETTINGS, ENGINE }

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
    var tabsRestored by remember { mutableStateOf(false) }

    val recentRaw by context.octoDataStore.data.collectAsState(initial = emptyPreferences())
    val recentDocuments = remember(recentRaw) {
        recentRaw[RECENT_KEY].orEmpty().lineSequence().mapNotNull(::decodeRecent).toList()
    }
    val favoriteUris = remember(recentRaw[FAVORITES_KEY]) {
        recentRaw[FAVORITES_KEY].orEmpty().lineSequence().filter { it.isNotBlank() }.toSet()
    }

    LaunchedEffect(Unit) {
        val stored = context.octoDataStore.data.first()
        val restoredTabs = stored[OPEN_TABS_KEY].orEmpty().lineSequence().mapNotNull(::decodeRecent)
        openTabs.addAll(restoredTabs)
        if (incomingUri != null) {
            val incoming = RecentDocument(incomingUri, queryDisplayName(context, incomingUri))
            if (openTabs.none { it.uri == incomingUri }) openTabs.add(0, incoming)
            selectedUri = incomingUri
            documentName = incoming.name
        } else {
            val activeUri = stored[ACTIVE_TAB_KEY]?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            val active = openTabs.firstOrNull { it.uri == activeUri } ?: openTabs.firstOrNull()
            selectedUri = active?.uri
            documentName = active?.name.orEmpty()
            if (active != null) section = AppSection.READER
        }
        tabsRestored = true
    }

    LaunchedEffect(openTabs.toList(), selectedUri, tabsRestored) {
        if (tabsRestored) {
            context.octoDataStore.edit { prefs ->
                prefs[OPEN_TABS_KEY] = openTabs.joinToString("\n") { encodeRecent(it.uri, it.name) }
                prefs[ACTIVE_TAB_KEY] = selectedUri?.toString().orEmpty()
            }
        }
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

    fun toggleFavorite(document: RecentDocument) {
        scope.launch {
            context.octoDataStore.edit { prefs ->
                val updated = prefs[FAVORITES_KEY].orEmpty().lineSequence()
                    .filter { it.isNotBlank() }.toMutableSet()
                val key = document.uri.toString()
                if (!updated.add(key)) updated.remove(key)
                prefs[FAVORITES_KEY] = updated.joinToString("\n")
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
                    RailDestination("الرئيسية", Icons.Default.Home, section == AppSection.RECENT) { section = AppSection.RECENT }
                    RailDestination("القارئ", Icons.Default.MenuBook, section == AppSection.READER) { section = AppSection.READER }
                    RailDestination("أدوات PDF", Icons.Default.Build, section == AppSection.TOOLS) { section = AppSection.TOOLS }
                    RailDestination("تقسيم", Icons.Default.ViewColumn, section == AppSection.SPLIT) { section = AppSection.SPLIT }
                    RailDestination("الإعدادات", Icons.Default.Settings, section == AppSection.SETTINGS || section == AppSection.ENGINE) { section = AppSection.SETTINGS }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Surface(color = Navy, tonalElevation = 2.dp) {
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
                                        AppSection.RECENT -> "مكتبة مستنداتك"
                                        AppSection.TOOLS -> "إدارة ومعالجة ملفات PDF"
                                        AppSection.SETTINGS -> "تفضيلات التطبيق"
                                        AppSection.ENGINE -> "تقنيات التطبيق"
                                    },
                                    fontSize = 10.sp, color = TextMuted
                                )
                            }
                            Surface(color = Color(0xFF25213F), shape = RoundedCornerShape(50)) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Cyan))
                                    Text("يعمل دون إنترنت", color = Color(0xFFC2B9FF), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            IconButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "فتح ملف", tint = Accent)
                            }
                        }
                        if ((section == AppSection.READER || section == AppSection.SPLIT) && openTabs.isNotEmpty()) {
                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier.fillMaxWidth().height(44.dp)
                                    .background(Color(0xFF0E1118)),
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
                                        color = if (active) Color(0xFF292443) else Color(0xFF191F2B),
                                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                                        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.45f)) else null
                                    ) {
                                        Row(
                                            Modifier.padding(start = 11.dp, end = 5.dp).fillMaxHeight(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, null, tint = if (active) Cyan else TextMuted, modifier = Modifier.size(15.dp))
                                            Text(tab.name, modifier = Modifier.widthIn(max = 145.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
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
                                    favoriteUris = favoriteUris,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpen = { openDocument(it.uri, it.name) },
                                    onToggleFavorite = { toggleFavorite(it) },
                                    onPick = { picker.launch(arrayOf("application/pdf")) },
                                    onTools = { section = AppSection.TOOLS },
                                    onSplit = { section = AppSection.SPLIT }
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
                            recent = recentDocuments, favoriteUris = favoriteUris,
                            modifier = Modifier.fillMaxSize(),
                            onOpen = { openDocument(it.uri, it.name) },
                            onToggleFavorite = { toggleFavorite(it) },
                            onPick = { picker.launch(arrayOf("application/pdf")) },
                            onTools = { section = AppSection.TOOLS },
                            onSplit = { section = AppSection.SPLIT }
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
                        AppSection.TOOLS -> PdfToolsScreen(Modifier.fillMaxSize())
                        AppSection.SETTINGS -> SettingsScreen(onEngineClick = { section = AppSection.ENGINE })
                        AppSection.ENGINE -> EngineInfoScreen()
                    }
                }

                if (!tablet) {
                    NavigationBar(containerColor = Color(0xFF0D1724), contentColor = TextMuted) {
                        NavigationBarItem(selected = section == AppSection.RECENT, onClick = { section = AppSection.RECENT },
                            icon = { Icon(Icons.Default.Home, contentDescription = "الرئيسية") }, label = { Text("الرئيسية", fontSize = 10.sp) })
                        NavigationBarItem(selected = section == AppSection.READER, onClick = { section = AppSection.READER },
                            icon = { Icon(Icons.Default.MenuBook, contentDescription = "القارئ") }, label = { Text("القارئ", fontSize = 10.sp) })
                        NavigationBarItem(selected = section == AppSection.TOOLS, onClick = { section = AppSection.TOOLS },
                            icon = { Icon(Icons.Default.Build, contentDescription = "أدوات PDF") }, label = { Text("الأدوات", fontSize = 10.sp) })
                        NavigationBarItem(selected = section == AppSection.SPLIT, onClick = { section = AppSection.SPLIT },
                            icon = { Icon(Icons.Default.ViewColumn, contentDescription = "تقسيم") }, label = { Text("تقسيم", fontSize = 10.sp) })
                        NavigationBarItem(selected = section == AppSection.SETTINGS || section == AppSection.ENGINE, onClick = { section = AppSection.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "الإعدادات") }, label = { Text("الإعدادات", fontSize = 10.sp) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onEngineClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("الإعدادات", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("إعدادات OctoPDF ومعلومات التطبيق", color = TextMuted, fontSize = 13.sp)
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("المظهر", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("واجهة داكنة ثابتة بألوان OctoPDF لتوفير تجربة قراءة مريحة.", color = TextMuted, fontSize = 12.sp)
                Text("الخصوصية", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("عمليات فتح وقراءة ومعالجة الملفات تتم محليًا على جهازك.", color = TextMuted, fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = onEngineClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Memory, null)
            Spacer(Modifier.width(8.dp))
            Text("معلومات محرك PDF")
        }
        Text("OctoPDF • Offline-first PDF workspace", color = TextMuted, fontSize = 11.sp)
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
    Column(
        modifier = Modifier.width(72.dp).clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                .background(if (selected) Color(0xFF173A4A) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) {
            Icon(icon, contentDescription = label, tint = if (selected) Cyan else TextMuted, modifier = Modifier.size(21.dp))
        }
        Text(label, fontSize = 10.sp, color = if (selected) Cyan else TextMuted, maxLines = 1)
    }
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
    favoriteUris: Set<String>,
    modifier: Modifier = Modifier,
    onOpen: (RecentDocument) -> Unit,
    onToggleFavorite: (RecentDocument) -> Unit,
    onPick: () -> Unit,
    onTools: () -> Unit,
    onSplit: () -> Unit
) {
    BoxWithConstraints(modifier.background(Ink)) {
        val tablet = maxWidth >= 600.dp
        val contentWidth = if (tablet) 900.dp else maxWidth
        var showFavoritesOnly by remember { mutableStateOf(false) }
        val visibleDocuments = remember(recent, favoriteUris, showFavoritesOnly) {
            if (showFavoritesOnly) recent.filter { it.uri.toString() in favoriteUris } else recent
        }
        LazyColumn(
            modifier = Modifier.widthIn(max = contentWidth).align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = if (tablet) 30.dp else 18.dp),
            contentPadding = PaddingValues(top = 22.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Navy),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(if (tablet) 28.dp else 21.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("مساحة عملك للمستندات", color = TextMuted, fontSize = 12.sp)
                                Text("كل ملفاتك. في مكان واحد.", fontSize = if (tablet) 27.sp else 23.sp, fontWeight = FontWeight.Bold)
                                Text("اقرأ، نظّم، وأنجز ملفات PDF بسهولة.", color = TextMuted, fontSize = 13.sp)
                            }
                            Box(
                                Modifier.size(if (tablet) 76.dp else 62.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF25213F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Accent, modifier = Modifier.size(38.dp))
                            }
                        }
                        Button(
                            onClick = onPick,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(9.dp))
                            Text("فتح ملف PDF", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = onTools,
                        modifier = Modifier.weight(1f),
                        color = Panel,
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF293044))
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Default.Build, null, tint = Cyan, modifier = Modifier.size(24.dp))
                            Text("أدوات PDF", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("دمج، تقسيم، تدوير وحماية", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    Surface(
                        onClick = onSplit,
                        modifier = Modifier.weight(1f),
                        color = Panel,
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF293044))
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Default.ViewColumn, null, tint = Accent, modifier = Modifier.size(24.dp))
                            Text("مقارنة مستندين", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("افتح ملفين جنبًا إلى جنب", color = TextMuted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ملفاتك", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("\${recent.size} مستند في سجلّك", color = TextMuted, fontSize = 12.sp)
                    }
                    Surface(color = PanelLight, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Description, null, tint = Cyan, modifier = Modifier.padding(10.dp).size(19.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showFavoritesOnly,
                        onClick = { showFavoritesOnly = false },
                        label = { Text("الكل") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF292443),
                            selectedLabelColor = TextPrimary
                        )
                    )
                    FilterChip(
                        selected = showFavoritesOnly,
                        onClick = { showFavoritesOnly = true },
                        leadingIcon = { Icon(Icons.Default.Star, null, modifier = Modifier.size(16.dp)) },
                        label = { Text("المفضلة · \${favoriteUris.size}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF292443),
                            selectedLabelColor = TextPrimary
                        )
                    )
                }
            }
            if (visibleDocuments.isEmpty()) {
                item {
                    Surface(
                        color = Panel,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(27.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Book, null, tint = TextMuted, modifier = Modifier.size(34.dp))
                            Text(
                                if (showFavoritesOnly) "لا توجد ملفات مفضلة بعد" else "مكتبتك جاهزة",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (showFavoritesOnly) "أضف أي مستند إلى المفضلة باستخدام النجمة."
                                else "افتح أول ملف PDF وسيظهر هنا لتعود إليه لاحقًا.",
                                color = TextMuted, fontSize = 12.sp
                            )
                            if (!showFavoritesOnly) {
                                TextButton(onClick = onPick) { Text("اختيار ملف", color = Cyan) }
                            }
                        }
                    }
                }
            } else {
                items(visibleDocuments, key = { it.uri.toString() }) { doc ->
                    Card(
                        onClick = { onOpen(doc) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(17.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(PanelLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Accent, modifier = Modifier.size(25.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("ملف PDF", color = TextMuted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { onToggleFavorite(doc) }) {
                                Icon(
                                    if (doc.uri.toString() in favoriteUris) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = if (doc.uri.toString() in favoriteUris) "إزالة من المفضلة" else "إضافة للمفضلة",
                                    tint = if (doc.uri.toString() in favoriteUris) Color(0xFFFFC857) else TextMuted
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item {
                Text(
                    "OCTOPDF  •  OFFLINE PDF WORKSPACE",
                    color = Color(0xFF687386),
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                )
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
    var searchOpen by remember(uri) { mutableStateOf(false) }
    var searchQuery by remember(uri) { mutableStateOf("") }
    var searchResults by remember(uri) { mutableStateOf<List<PdfSearchHit>>(emptyList()) }
    var searchLoading by remember(uri) { mutableStateOf(false) }
    var searchHasRun by remember(uri) { mutableStateOf(false) }
    var searchError by remember(uri) { mutableStateOf<String?>(null) }
    var pageJumpOpen by remember(uri) { mutableStateOf(false) }
    var requestedPage by remember(uri) { mutableStateOf((resumePage + 1).toString()) }

    fun performPdfSearch() {
        val query = searchQuery.trim()
        if (query.isEmpty() || searchLoading) return
        scope.launch {
            searchLoading = true
            searchHasRun = true
            searchError = null
            searchResults = emptyList()
            try {
                searchResults = searchPdfDocument(context, uri, query)
            } catch (error: Exception) {
                searchError = error.localizedMessage ?: "تعذر البحث داخل هذا المستند."
            } finally {
                searchLoading = false
            }
        }
    }

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
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Default.Search, contentDescription = "بحث داخل المستند", tint = Accent)
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
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("الانتقال إلى صفحة محددة") },
                                onClick = {
                                    requestedPage = pageNumber.toString()
                                    pageJumpOpen = true
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("الصفحة السابقة") },
                                enabled = pageNumber > 1,
                                onClick = { pdfView?.jumpTo((pageNumber - 2).coerceAtLeast(0), true); menuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("الصفحة التالية") },
                                enabled = totalPages > 0 && pageNumber < totalPages,
                                onClick = { pdfView?.jumpTo(pageNumber.coerceAtLeast(0), true); menuExpanded = false }
                            )
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

    if (pageJumpOpen) {
        AlertDialog(
            onDismissRequest = { pageJumpOpen = false },
            title = { Text("الانتقال إلى صفحة") },
            text = {
                OutlinedTextField(
                    value = requestedPage,
                    onValueChange = { requestedPage = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text(if (totalPages > 0) "رقم الصفحة (1-$totalPages)" else "رقم الصفحة") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = requestedPage.toIntOrNull()
                    if (target != null && target in 1..totalPages) {
                        pdfView?.jumpTo(target - 1, true)
                        pageJumpOpen = false
                    }
                }, enabled = requestedPage.toIntOrNull()?.let { it in 1..totalPages } == true) { Text("انتقال") }
            },
            dismissButton = { TextButton(onClick = { pageJumpOpen = false }) { Text("إلغاء") } }
        )
    }

    if (searchOpen) {
        AlertDialog(
            onDismissRequest = { searchOpen = false },
            title = { Text("بحث داخل المستند") },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            searchHasRun = false
                            searchError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("كلمة أو عبارة") }
                    )
                    if (searchLoading) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("جارٍ فحص صفحات المستند…", color = TextMuted)
                        }
                    }
                    searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (searchHasRun && !searchLoading && searchError == null && searchResults.isEmpty()) {
                        Text("لم يتم العثور على النص. قد يكون المستند ممسوحًا ضوئيًا كصورة.", color = TextMuted)
                    }
                    if (searchResults.isNotEmpty()) {
                        Text("عُثر على النص في ${searchResults.size} صفحة", color = Cyan, fontSize = 12.sp)
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 270.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(searchResults) { hit ->
                                TextButton(
                                    onClick = {
                                        pdfView?.jumpTo(hit.pageIndex, true)
                                        searchOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            "صفحة ${hit.pageIndex + 1} · ${hit.matchCount} تطابق",
                                            color = Cyan,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            hit.snippet,
                                            color = TextMuted,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { performPdfSearch() },
                    enabled = searchQuery.isNotBlank() && !searchLoading
                ) { Text("بحث") }
            },
            dismissButton = {
                TextButton(onClick = { searchOpen = false }) { Text("إغلاق") }
            }
        )
    }

}

