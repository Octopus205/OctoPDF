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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF101820)
private val Navy = Color(0xFF142B45)
private val Blue = Color(0xFF4C9BE8)
private val SurfaceDark = Color(0xFF182330)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OctoPdfApp(intent?.data) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OctoPdfApp(incomingUri: Uri?) {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf(incomingUri) }
    var documentName by remember { mutableStateOf(incomingUri?.lastPathSegment ?: "") }
    var pageCount by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(listOf<String>()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            selectedUri = uri
            documentName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        loading = true
        errorMessage = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("تعذر فتح الملف")
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val count = renderer.pageCount
                        val output = ArrayList<Bitmap>()
                        for (index in 0 until count) {
                            renderer.openPage(index).use { page ->
                                val width = (page.width * 1.35f).toInt().coerceAtMost(1600)
                                val height = (page.height * width.toFloat() / page.width).toInt()
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                output.add(bitmap)
                            }
                        }
                        count to output
                    }
                }
            }
            pageCount = rendered.first
            pages = rendered.second
            recent = (listOf(documentName) + recent.filterNot { it == documentName }).take(6)
        } catch (e: Exception) {
            errorMessage = e.message ?: "حدث خطأ أثناء قراءة PDF"
            pages = emptyList()
        } finally { loading = false }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, onPrimary = Color.White, background = Ink, surface = SurfaceDark, onSurface = Color(0xFFEAF1F8))) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("OctoPDF", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                            Text("قارئ PDF • يعمل بدون إنترنت", fontSize = 11.sp, color = Color.LightGray)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy),
                    actions = {
                        IconButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "فتح ملف", tint = Color.White)
                        }
                    }
                )
            },
            containerColor = Ink
        ) { padding ->
            if (selectedUri == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 30.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Navy)) {
                            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Blue, modifier = Modifier.size(42.dp))
                                Text("كل ملفاتك في مكان واحد", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("افتح مستندات PDF واقرأها بسهولة، مع تجربة مصممة للهواتف والتابلت.", color = Color(0xFFCBD8E6))
                                Button(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.FolderOpen, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("فتح ملف PDF")
                                }
                            }
                        }
                    }
                    item { Text("الأخيرة", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                    if (recent.isEmpty()) item { Text("لا توجد ملفات حديثة بعد. ابدأ بفتح ملف PDF.", color = Color.LightGray) }
                    items(recent) { name ->
                        ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Default.PictureAsPdf, null, tint = Blue) }, colors = ListItemDefaults.colors(containerColor = SurfaceDark))
                    }
                    item { Text("الإصدار الأولي 0.1.0", color = Color.Gray, fontSize = 12.sp) }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(documentName, maxLines = 1, fontWeight = FontWeight.SemiBold)
                            Text("$pageCount صفحة", color = Color.LightGray, fontSize = 12.sp)
                        }
                        TextButton(onClick = { selectedUri = null; pages = emptyList() }) { Text("إغلاق") }
                    }
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        errorMessage != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error) }
                        else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(pages.size) { index ->
                                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                    Image(bitmap = pages[index].asImageBitmap(), contentDescription = "صفحة ${index + 1}", modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
