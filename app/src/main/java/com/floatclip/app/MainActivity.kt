package com.floatclip.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.floatclip.app.data.FloatClipDao
import com.floatclip.app.data.FloatClipDatabase
import com.floatclip.app.data.QuickTextEntity
import com.floatclip.app.data.SavedClipboardEntity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = FloatClipDatabase.get(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2563EB),
                    secondary = Color(0xFF0F766E),
                    background = Color(0xFFF5F7FB),
                    surface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FloatClipScreen(database)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatClipScreen(database: FloatClipDatabase) {
    val context = LocalContext.current
    val dao = remember(database) { database.dao() }
    val quickTexts by dao.observeQuickTexts().collectAsState(initial = emptyList())
    val aItems by dao.observeSaved("A").collectAsState(initial = emptyList())
    val cItems by dao.observeSaved("C").collectAsState(initial = emptyList())
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("FloatClip", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Floating clipboard and authenticator",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    ClipboardTools.toast(context, "Allow Display over other apps, then return and tap Start Floating Bubble again.")
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.packageName)
                        )
                    )
                } else {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, FloatingBubbleService::class.java)
                    )
                    ClipboardTools.toast(context, "FloatClip bubble started.")
                }
            }
        ) {
            Text("Start Floating Bubble")
        }
        Text(
            "Bubble buttons: 1 Copy Next · 2 Save to A · 3 Save to C · 4 2FA · 5 Close",
            modifier = Modifier.padding(top = 7.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TabRow(selectedTabIndex = selectedTab) {
            listOf("Quick Text", "A Sheet", "C Sheet", "2FA").forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label, maxLines = 1) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        when (selectedTab) {
            0 -> QuickTextSpreadsheet(quickTexts, dao)
            1 -> SavedClipboardSpreadsheet("A", aItems, dao)
            2 -> SavedClipboardSpreadsheet("C", cItems, dao)
            else -> TwoFactorSheet()
        }
    }
}

@Composable
private fun QuickTextSheet(items: List<QuickTextEntity>, dao: FloatClipDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editorOpen by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<QuickTextEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<QuickTextEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Quick Text Queue", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(items.size.toString() + " saved text(s)", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                editingItem = null
                editorOpen = true
            }) {
                Text("+ Add")
            }
        }

        Text(
            "Bubble Button 1 copies the next item in this order, then loops to the beginning.",
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (items.isEmpty()) {
            EmptyState(
                title = "Your queue is empty",
                message = "Add your first reusable text. It will be available from the floating bubble."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    QuickTextCard(
                        item = item,
                        index = index,
                        total = items.size,
                        onCopy = {
                            ClipboardTools.copyText(context, item.title, item.content)
                            ClipboardTools.toast(context, "Copied: " + item.title)
                        },
                        onEdit = {
                            editingItem = item
                            editorOpen = true
                        },
                        onDelete = { pendingDelete = item },
                        onMove = { from, to ->
                            scope.launch {
                                val reordered = items.toMutableList()
                                if (from in reordered.indices && to in reordered.indices) {
                                    val moved = reordered.removeAt(from)
                                    reordered.add(to, moved)
                                    reordered.forEachIndexed { position, entry ->
                                        dao.updateQuickTextPosition(entry.id, position)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (editorOpen) {
        QuickTextEditorDialog(
            initial = editingItem,
            onDismiss = { editorOpen = false },
            onSave = { title, content ->
                val current = editingItem
                scope.launch {
                    if (current == null) {
                        val position = (dao.maxQuickTextPosition() ?: -1) + 1
                        dao.insertQuickText(
                            QuickTextEntity(
                                title = title,
                                content = content,
                                position = position
                            )
                        )
                    } else {
                        dao.updateQuickText(current.copy(title = title, content = content))
                    }
                }
                editorOpen = false
                editingItem = null
            }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Quick Text?") },
            text = { Text("This will permanently delete “" + item.title + "” from the queue.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { dao.deleteQuickText(item.id) }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun QuickTextCard(
    item: QuickTextEntity,
    index: Int,
    total: Int,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        (index + 1).toString() + ". " + item.title,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        item.content,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = onCopy) { Text("Copy") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = index > 0,
                    onClick = { onMove(index, index - 1) },
                    modifier = Modifier.weight(1f)
                ) { Text("↑", fontSize = 17.sp) }
                OutlinedButton(
                    enabled = index < total - 1,
                    onClick = { onMove(index, index + 1) },
                    modifier = Modifier.weight(1f)
                ) { Text("↓", fontSize = 17.sp) }
                TextButton(onClick = onEdit, modifier = Modifier.weight(1.3f)) { Text("Edit") }
                TextButton(onClick = onDelete, modifier = Modifier.weight(1.3f)) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun QuickTextEditorDialog(
    initial: QuickTextEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var content by remember(initial?.id) { mutableStateOf(initial?.content ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Quick Text" else "Edit Quick Text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name (e.g. Greeting)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Text to copy") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = { onSave(title.ifBlank { "Text" }, content) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SavedClipboardSheet(
    slot: String,
    items: List<SavedClipboardEntity>,
    dao: FloatClipDao
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingItem by remember { mutableStateOf<SavedClipboardEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedClipboardEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("$slot Sheet", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(
            "Every paste adds a new row. Previous rows are kept.",
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val content = ClipboardTools.readText(context)
                if (content == null) {
                    ClipboardTools.toast(context, "Clipboard is empty. Copy text first.")
                } else {
                    scope.launch {
                        dao.insertSaved(SavedClipboardEntity(slot = slot, content = content))
                        val count = dao.countSaved(slot)
                        withContext(Dispatchers.Main) {
                            ClipboardTools.toast(context, "Added a new row to $slot Sheet (#$count)")
                        }
                    }
                }
            }
        ) {
            Text("Paste Clipboard to $slot")
        }

        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) {
            EmptyState(
                title = "$slot Sheet is empty",
                message = "Copy text in another app, then use bubble button " +
                    (if (slot == "A") "2" else "3") +
                    " or the Paste Clipboard button above."
            )
        } else {
            Text(items.size.toString() + " saved row(s)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    SavedClipboardCard(
                        index = index,
                        item = item,
                        onCopy = {
                            ClipboardTools.copyText(context, "$slot saved text", item.content)
                            ClipboardTools.toast(context, "Copied from $slot Sheet")
                        },
                        onEdit = { editingItem = item },
                        onDelete = { pendingDelete = item }
                    )
                }
            }
        }
    }

    editingItem?.let { item ->
        SavedTextEditorDialog(
            initialContent = item.content,
            onDismiss = { editingItem = null },
            onSave = { content ->
                editingItem = null
                scope.launch { dao.updateSaved(item.copy(content = content)) }
            }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved row?") },
            text = { Text("This row will be removed from the $slot Sheet.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { dao.deleteSaved(item.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SavedClipboardCard(
    index: Int,
    item: SavedClipboardEntity,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Row " + (index + 1), fontWeight = FontWeight.SemiBold)
            Text(
                item.content,
                modifier = Modifier.padding(top = 5.dp),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)),
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SavedTextEditorDialog(
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit saved text") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Text") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = content.isNotBlank(), onClick = { onSave(content) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TwoFactorSheet() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var generatedWindow by remember { mutableStateOf<Long?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TOTP Authenticator", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Copy a Base32 TOTP secret key from your authenticator setup, then tap the button below. FloatClip processes the secret locally and does not save it.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val secret = ClipboardTools.readText(context)
                if (secret == null) {
                    errorMessage = "Clipboard is empty. Copy your 2FA secret key first."
                    return@Button
                }
                scope.launch {
                    try {
                        val code = withContext(Dispatchers.Default) { TotpGenerator.generate(secret) }
                        ClipboardTools.copyText(context, "FloatClip 2FA code", code)
                        generatedCode = code
                        generatedWindow = System.currentTimeMillis() / 30_000L
                        errorMessage = null
                        ClipboardTools.toast(context, "2FA code copied.")
                    } catch (exception: Exception) {
                        errorMessage = exception.message ?: "Could not generate a code."
                        generatedCode = null
                        generatedWindow = null
                    }
                }
            }
        ) {
            Text("Generate & Copy Code from Clipboard")
        }

        if (errorMessage != null) {
            Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Current code", color = Color(0xFF1D4ED8))
                Spacer(Modifier.height(8.dp))
                val currentWindow = now / 30_000L
                val visibleCode = generatedCode?.takeIf { generatedWindow == currentWindow }
                if (visibleCode != null) {
                    Text(
                        visibleCode,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        color = Color(0xFF1D4ED8)
                    )
                    Text(
                        "Expires in " + TotpGenerator.secondsRemaining(now) + " seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1D4ED8)
                    )
                } else {
                    Text(
                        if (generatedCode == null) "------" else "Expired",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D4ED8)
                    )
                    Text("Generate a fresh code when needed.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            "Security: only use a secret from an account you own or are authorized to access. Keep both the secret and generated code private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
