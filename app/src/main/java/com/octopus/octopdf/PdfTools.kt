package com.octopus.octopdf

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class PdfOperation(val title: String, val description: String) {
    MERGE("دمج ملفات PDF", "اجمع عدة ملفات في مستند واحد"),
    EXTRACT("تقسيم / استخراج صفحات", "احفظ صفحات أو نطاقات محددة في ملف PDF مستقل"),
    ROTATE("تدوير الصفحات", "دوّر صفحات المستند 90 درجة"),
    REORDER("إعادة ترتيب الصفحات", "اكتب ترتيب الصفحات الجديد، مثل 3,1,2"),
    PROTECT("حماية بكلمة مرور", "أنشئ نسخة مشفرة من ملف PDF")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfToolsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf(PdfOperation.MERGE) }
    var sources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var range by remember { mutableStateOf("1-3") }
    var order by remember { mutableStateOf("1,2,3") }
    var password by remember { mutableStateOf("") }
    var outputName by remember { mutableStateOf("OctoPDF-Result.pdf") }
    var status by remember { mutableStateOf("اختر أداة ثم حدد ملفات PDF للبدء.") }
    var busy by remember { mutableStateOf(false) }

    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sources = listOf(uri)
            status = "تم اختيار الملف: ${uri.lastPathSegment}"
        }
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            sources = uris
            status = "تم اختيار ${uris.size} ملفات."
        }
    }

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { outputUri ->
        if (outputUri != null && sources.isNotEmpty()) {
            scope.launch {
                busy = true
                status = "جارٍ تنفيذ العملية…"
                val result = withContext(Dispatchers.IO) {
                    runCatching { runPdfOperation(context, operation, sources, outputUri, range, order, password) }
                }
                status = result.fold(
                    { "اكتملت العملية بنجاح. تم حفظ الملف في الموقع الذي اخترته." },
                    { "تعذر تنفيذ العملية: ${it.localizedMessage ?: "خطأ غير معروف"}" }
                )
                busy = false
                sources = emptyList()
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("أدوات PDF", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5F6FC))
                Text("إدارة ملفاتك محليًا على الجهاز دون رفعها إلى أي خادم.", color = Color(0xFF9AA5B8), fontSize = 13.sp)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfOperation.entries.forEach { item ->
                        val selected = operation == item
                        Surface(
                            onClick = { operation = item; sources = emptyList() },
                            color = if (selected) Color(0xFF292443) else Color(0xFF171C27),
                            shape = RoundedCornerShape(16.dp),
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B7CFF)) else null
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when (item) {
                                        PdfOperation.MERGE -> Icons.Default.MergeType
                                        PdfOperation.EXTRACT -> Icons.Default.CallSplit
                                        PdfOperation.ROTATE -> Icons.Default.RotateRight
                                        PdfOperation.REORDER -> Icons.Default.Reorder
                                        PdfOperation.PROTECT -> Icons.Default.Lock
                                    }, null, tint = Color(0xFF66C8FF), modifier = Modifier.size(25.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, color = Color(0xFFF5F6FC), fontWeight = FontWeight.SemiBold)
                                    Text(item.description, color = Color(0xFF9AA5B8), fontSize = 11.sp)
                                }
                                RadioButton(selected = selected, onClick = { operation = item; sources = emptyList() })
                            }
                        }
                    }
                }
            }
            if (operation == PdfOperation.EXTRACT) {
                item {
                    OutlinedTextField(value = range, onValueChange = { range = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("نطاق الصفحات") }, supportingText = { Text("مثال: 1-4 أو 2,5,7") }, singleLine = true)
                }
            }
            if (operation == PdfOperation.REORDER) {
                item {
                    OutlinedTextField(value = order, onValueChange = { order = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("الترتيب الجديد") }, supportingText = { Text("مثال: 3,1,2 — يجب إدراج كل صفحة مرة واحدة") }, singleLine = true)
                }
            }
            if (operation == PdfOperation.PROTECT) {
                item {
                    OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("كلمة المرور") }, singleLine = true)
                }
            }
            item {
                OutlinedTextField(value = outputName, onValueChange = { outputName = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("اسم الملف الناتج") }, singleLine = true)
            }
            item {
                Button(
                    onClick = {
                        if (operation == PdfOperation.MERGE) multiPicker.launch(arrayOf("application/pdf"))
                        else singlePicker.launch(arrayOf("application/pdf"))
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !busy && (operation != PdfOperation.PROTECT || password.length >= 4)
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (operation == PdfOperation.MERGE) "اختيار ملفات PDF" else "اختيار ملف PDF")
                }
                if (sources.isNotEmpty()) {
                    Text("الملفات المحددة: ${sources.size}", color = Color(0xFF66C8FF), fontSize = 12.sp)
                    Button(onClick = { outputPicker.launch(outputName.ifBlank { "OctoPDF-Result.pdf" }) },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("تنفيذ وحفظ النتيجة") }
                }
            }
            item {
                Surface(color = Color(0xFF171C27), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF66C8FF))
                        else Icon(Icons.Default.FolderOpen, null, tint = Color(0xFF66C8FF))
                        Spacer(Modifier.width(10.dp))
                        Text(status, color = Color(0xFF9AA5B8), fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("ملاحظة: الملفات الأصلية لا يتم تعديلها؛ كل عملية تنشئ ملفًا جديدًا.", color = Color(0xFF9AA5B8), fontSize = 11.sp)
            }
        }
    }
}

private fun runPdfOperation(
    context: Context,
    operation: PdfOperation,
    sources: List<Uri>,
    output: Uri,
    range: String,
    order: String,
    password: String
) {
    PDFBoxResourceLoader.init(context.applicationContext)
    val resolver = context.contentResolver
    val outputStream = resolver.openOutputStream(output) ?: throw IOException("تعذر إنشاء ملف النتيجة.")
    outputStream.use { stream ->
        when (operation) {
            PdfOperation.MERGE -> {
                require(sources.size >= 2) { "اختر ملفين على الأقل للدمج." }
                PDDocument().use { destination ->
                    sources.forEach { uri ->
                        val input = resolver.openInputStream(uri) ?: throw IOException("تعذر فتح أحد الملفات.")
                        input.use { sourceStream ->
                            PDDocument.load(sourceStream).use { source ->
                                for (index in 0 until source.numberOfPages) destination.importPage(source.getPage(index))
                            }
                        }
                    }
                    destination.save(stream)
                }
            }
            PdfOperation.EXTRACT -> {
                require(sources.size == 1) { "اختر ملفًا واحدًا لاستخراج الصفحات." }
                val input = resolver.openInputStream(sources.first()) ?: throw IOException("تعذر فتح الملف.")
                input.use { sourceStream ->
                    PDDocument.load(sourceStream).use { source ->
                        val indexes = parsePageSelection(range, source.numberOfPages)
                        PDDocument().use { destination ->
                            indexes.forEach { destination.importPage(source.getPage(it)) }
                            destination.save(stream)
                        }
                    }
                }
            }
            PdfOperation.ROTATE -> {
                require(sources.size == 1) { "اختر ملفًا واحدًا للتدوير." }
                val input = resolver.openInputStream(sources.first()) ?: throw IOException("تعذر فتح الملف.")
                input.use { sourceStream ->
                    PDDocument.load(sourceStream).use { document ->
                        for (index in 0 until document.numberOfPages) {
                            val page = document.getPage(index)
                            page.rotation = (page.rotation + 90) % 360
                        }
                        document.save(stream)
                    }
                }
            }
            PdfOperation.REORDER -> {
                require(sources.size == 1) { "اختر ملفًا واحدًا لإعادة الترتيب." }
                val input = resolver.openInputStream(sources.first()) ?: throw IOException("تعذر فتح الملف.")
                input.use { sourceStream ->
                    PDDocument.load(sourceStream).use { source ->
                        val indexes = parsePageSelection(order, source.numberOfPages)
                        require(indexes.size == source.numberOfPages) { "أدخل ترتيبًا يتضمن جميع الصفحات دون تكرار." }
                        PDDocument().use { destination ->
                            indexes.forEach { destination.importPage(source.getPage(it)) }
                            destination.save(stream)
                        }
                    }
                }
            }
            PdfOperation.PROTECT -> {
                require(sources.size == 1) { "اختر ملفًا واحدًا للحماية." }
                require(password.length >= 4) { "كلمة المرور يجب أن تتكون من 4 أحرف على الأقل." }
                val input = resolver.openInputStream(sources.first()) ?: throw IOException("تعذر فتح الملف.")
                input.use { sourceStream ->
                    PDDocument.load(sourceStream).use { document ->
                        val policy = StandardProtectionPolicy(password, password, AccessPermission()).apply {
                            encryptionKeyLength = 128
                        }
                        document.protect(policy)
                        document.save(stream)
                    }
                }
            }
        }
    }
}

private fun parsePageSelection(value: String, pageCount: Int): List<Int> {
    val result = mutableListOf<Int>()
    value.split(',').forEach { token ->
        val part = token.trim()
        if (part.contains('-')) {
            val bounds = part.split('-', limit = 2)
            require(bounds.size == 2) { "نطاق الصفحات غير صحيح." }
            val first = bounds[0].trim().toIntOrNull() ?: throw IllegalArgumentException("رقم صفحة غير صحيح.")
            val last = bounds[1].trim().toIntOrNull() ?: throw IllegalArgumentException("رقم صفحة غير صحيح.")
            require(first >= 1 && last >= first && last <= pageCount) { "نطاق الصفحات خارج حدود المستند." }
            result.addAll((first - 1) until last)
        } else {
            val page = part.toIntOrNull() ?: throw IllegalArgumentException("رقم صفحة غير صحيح.")
            require(page in 1..pageCount) { "رقم الصفحة خارج حدود المستند." }
            result += page - 1
        }
    }
    require(result.isNotEmpty() && result.distinct().size == result.size) { "حدد صفحات صحيحة دون تكرار." }
    return result
}
