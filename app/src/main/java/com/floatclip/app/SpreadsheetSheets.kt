package com.floatclip.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floatclip.app.data.ClipboardSheetEntity
import com.floatclip.app.data.FloatClipDao
import com.floatclip.app.data.QuickTextEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val GridLine = Color(0xFFB8C2C7)
private val GridText = Color(0xFF202124)

private data class SheetPalette(
    val rowHeader: Color,
    val columns: List<Color>
)

private val sheetPalettes = listOf(
    SheetPalette(
        rowHeader = Color(0xFFE91E63),
        columns = listOf(Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFF9800), Color(0xFF9C27B0))
    ),
    SheetPalette(
        rowHeader = Color(0xFF0F766E),
        columns = listOf(Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFEA580C), Color(0xFFDB2777))
    ),
    SheetPalette(
        rowHeader = Color(0xFF475569),
        columns = listOf(Color(0xFF0891B2), Color(0xFF65A30D), Color(0xFFD97706), Color(0xFF9333EA))
    )
)

private val columnNames = listOf("COL A", "COL B", "COL C", "COL D")

@Composable
fun QuickTextSpreadsheet(items: List<QuickTextEntity>, dao: FloatClipDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    val editMutex = remember { Mutex() }
    var paletteIndex by remember { mutableStateOf(0) }
    var selectedRow by remember { mutableStateOf(0) }
    var showPurgeDialog by remember { mutableStateOf(false) }
    val palette = sheetPalettes[paletteIndex]
    val rowCount = maxOf(16, items.size + 1)
    val rows = (0 until rowCount).map { index ->
        items.getOrNull(index) ?: QuickTextEntity(
            id = -(index + 1L),
            title = "",
            content = "",
            position = index
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        SheetToolbar(
            palette = palette,
            columnCount = 1,
            selectedCellLabel = "A${selectedRow + 1}",
            onPaste = {
                val clipboardText = ClipboardTools.readText(context)
                if (clipboardText.isNullOrBlank()) {
                    ClipboardTools.toast(context, "Clipboard is empty. Copy text first.")
                } else {
                    val pastedRows = parseClipboardGrid(clipboardText).map { it.firstOrNull().orEmpty() }
                    val startRow = selectedRow
                    scope.launch {
                        editMutex.withLock {
                            pastedRows.forEachIndexed { offset, value ->
                                updateQuickTextCell(dao, startRow + offset, value)
                            }
                        }
                        selectedRow = startRow + pastedRows.lastIndex.coerceAtLeast(0)
                        ClipboardTools.toast(context, "Pasted ${pastedRows.size} row(s) into Column A.")
                    }
                }
            },
            onCopyColumn = {
                val values = items.map { it.content }.filter { it.isNotBlank() }
                if (values.isEmpty()) {
                    ClipboardTools.toast(context, "Column A is empty.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip Quick Text", values.joinToString("\n"))
                    ClipboardTools.toast(context, "Column A copied.")
                }
            },
            onCsv = {
                val csv = buildCsv(
                    listOf("COL A"),
                    items.filter { it.content.isNotBlank() }.map { listOf(it.content) }
                )
                if (csv.isBlank()) ClipboardTools.toast(context, "No records to export.")
                else exportCsv(context, "QuickText", csv)
            },
            onPalette = { paletteIndex = (paletteIndex + 1) % sheetPalettes.size }
        )

        PurgeAllButton(onClick = { showPurgeDialog = true })
        SpreadsheetHeader(horizontalState, palette, columnCount = 1)

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    SpreadsheetRowNumber(index + 1, palette.rowHeader)
                    Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                        SpreadsheetCell(
                            rowIndex = index,
                            columnIndex = 0,
                            value = item.content,
                            width = 310.dp,
                            accent = palette.columns[0],
                            selected = selectedRow == index,
                            onSelect = { selectedRow = index },
                            onValueChange = { value ->
                                scope.launch {
                                    editMutex.withLock { updateQuickTextCell(dao, index, value) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showPurgeDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeDialog = false },
            title = { Text("Purge all records?") },
            text = { Text("This permanently deletes every Quick Text row. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeDialog = false
                    scope.launch {
                        dao.deleteAllQuickTexts()
                        ClipboardTools.toast(context, "All Quick Text records deleted.")
                    }
                }) { Text("Purge all") }
            },
            dismissButton = { TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ClipboardSheetSpreadsheet(items: List<ClipboardSheetEntity>, dao: FloatClipDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    val editMutex = remember { Mutex() }
    var paletteIndex by remember { mutableStateOf(0) }
    var selectedRow by remember { mutableStateOf(0) }
    var selectedColumn by remember { mutableStateOf(0) }
    var showPurgeDialog by remember { mutableStateOf(false) }
    val palette = sheetPalettes[paletteIndex]
    val rowCount = maxOf(16, items.size + 1)
    val rows = (0 until rowCount).map { index ->
        items.getOrNull(index) ?: ClipboardSheetEntity(position = index)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        SheetToolbar(
            palette = palette,
            columnCount = 4,
            selectedCellLabel = columnNames[selectedColumn].last().toString() + (selectedRow + 1),
            onPaste = {
                val clipboardText = ClipboardTools.readText(context)
                if (clipboardText.isNullOrBlank()) {
                    ClipboardTools.toast(context, "Clipboard is empty. Copy text first.")
                } else {
                    val grid = parseClipboardGrid(clipboardText)
                    val startRow = selectedRow
                    val startColumn = selectedColumn
                    scope.launch {
                        editMutex.withLock {
                            grid.forEachIndexed { rowOffset, values ->
                                values.forEachIndexed { columnOffset, value ->
                                    val destinationColumn = startColumn + columnOffset
                                    if (destinationColumn in 0..3) {
                                        updateClipboardCell(dao, startRow + rowOffset, destinationColumn, value)
                                    }
                                }
                            }
                        }
                        selectedRow = startRow + grid.lastIndex.coerceAtLeast(0)
                        selectedColumn = (startColumn + (grid.lastOrNull()?.lastIndex ?: 0)).coerceAtMost(3)
                        val requestedCells = grid.sumOf { it.size }
                        val acceptedCells = grid.sumOf { values ->
                            values.indices.count { startColumn + it in 0..3 }
                        }
                        val suffix = if (acceptedCells < requestedCells) " Extra cells beyond COL D were skipped." else ""
                        ClipboardTools.toast(context, "Pasted $acceptedCells cell(s) from ${columnNames[startColumn].last()} ${startRow + 1}.$suffix")
                    }
                }
            },
            onCopyColumn = { column ->
                val values = items.map { clipboardCell(it, column) }.filter { it.isNotBlank() }
                if (values.isEmpty()) {
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " is empty.")
                } else {
                    ClipboardTools.copyText(
                        context,
                        "FloatClip " + columnNames[column],
                        values.joinToString("\n")
                    )
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " copied.")
                }
            },
            onCsv = {
                val csv = buildCsv(
                    columnNames,
                    items.filter {
                        it.colA.isNotBlank() || it.colB.isNotBlank() ||
                            it.colC.isNotBlank() || it.colD.isNotBlank()
                    }.map { listOf(it.colA, it.colB, it.colC, it.colD) }
                )
                if (csv.isBlank()) ClipboardTools.toast(context, "No records to export.")
                else exportCsv(context, "ClipboardSheet", csv)
            },
            onPalette = { paletteIndex = (paletteIndex + 1) % sheetPalettes.size }
        )

        PurgeAllButton(onClick = { showPurgeDialog = true })
        SpreadsheetHeader(horizontalState, palette, columnCount = 4)

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    SpreadsheetRowNumber(index + 1, palette.rowHeader)
                    Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                        for (column in 0..3) {
                            SpreadsheetCell(
                                rowIndex = index,
                                columnIndex = column,
                                value = clipboardCell(item, column),
                                width = 150.dp,
                                accent = palette.columns[column],
                                selected = selectedRow == index && selectedColumn == column,
                                onSelect = {
                                    selectedRow = index
                                    selectedColumn = column
                                },
                                onValueChange = { value ->
                                    scope.launch {
                                        editMutex.withLock {
                                            updateClipboardCell(dao, index, column, value)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPurgeDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeDialog = false },
            title = { Text("Purge all records?") },
            text = { Text("This permanently deletes every row in the Clipboard Sheet. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeDialog = false
                    scope.launch {
                        dao.deleteAllClipboardSheet()
                        ClipboardTools.toast(context, "All Clipboard Sheet records deleted.")
                    }
                }) { Text("Purge all") }
            },
            dismissButton = { TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SheetToolbar(
    palette: SheetPalette,
    columnCount: Int,
    selectedCellLabel: String,
    onPaste: () -> Unit,
    onCopyColumn: (Int) -> Unit,
    onCsv: () -> Unit,
    onPalette: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPaste,
            modifier = Modifier.width(76.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A73E8),
                contentColor = Color.White
            )
        ) { Text("Paste $selectedCellLabel", fontSize = 10.sp, maxLines = 1) }

        for (column in 0 until columnCount) {
            Button(
                onClick = { onCopyColumn(column) },
                modifier = Modifier.width(if (columnCount == 1) 82.dp else 64.dp).height(40.dp),
                shape = RoundedCornerShape(5.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.columns[column],
                    contentColor = Color.White
                )
            ) {
                Text("Copy " + columnNames[column].last(), fontSize = 11.sp, maxLines = 1)
            }
        }
        Button(
            onClick = onCsv,
            modifier = Modifier.width(48.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE91E63),
                contentColor = Color.White
            )
        ) { Text("CSV", fontSize = 12.sp) }

        Button(
            onClick = onPalette,
            modifier = Modifier.width(40.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00ACC1),
                contentColor = Color.White
            )
        ) { Text("🎨", fontSize = 17.sp) }
    }
}

@Composable
private fun PurgeAllButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp).height(40.dp),
        shape = RoundedCornerShape(5.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFD32F2F),
            contentColor = Color.White
        )
    ) {
        Text("PURGE ALL RECORDS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SpreadsheetHeader(
    horizontalState: androidx.compose.foundation.ScrollState,
    palette: SheetPalette,
    columnCount: Int
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.width(42.dp).height(46.dp)
                .background(palette.rowHeader)
                .border(1.dp, GridLine),
            contentAlignment = Alignment.Center
        ) {
            Text("#", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Row(modifier = Modifier.horizontalScroll(horizontalState)) {
            for (column in 0 until columnCount) {
                Box(
                    modifier = Modifier
                        .width(if (columnCount == 1) 310.dp else 150.dp)
                        .height(46.dp)
                        .background(palette.columns[column])
                        .border(1.dp, GridLine),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        columnNames[column],
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SpreadsheetRowNumber(number: Int, color: Color) {
    Box(
        modifier = Modifier.width(42.dp).height(56.dp)
            .background(color)
            .border(1.dp, GridLine),
        contentAlignment = Alignment.Center
    ) {
        Text(number.toString(), color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun SpreadsheetCell(
    rowIndex: Int,
    columnIndex: Int,
    value: String,
    width: Dp,
    accent: Color,
    selected: Boolean,
    onSelect: () -> Unit,
    onValueChange: (String) -> Unit
) {
    var localText by remember(rowIndex, columnIndex) { mutableStateOf(value) }
    var isFocused by remember(rowIndex, columnIndex) { mutableStateOf(false) }

    LaunchedEffect(value, isFocused) {
        if (!isFocused && localText != value) localText = value
    }

    Box(
        modifier = Modifier.width(width).height(56.dp)
            .background(if (selected) Color(0xFFE8F0FE) else Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF1A73E8) else GridLine)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = localText,
            onValueChange = {
                localText = it
                onValueChange(it)
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onSelect()
            },
            textStyle = TextStyle(color = GridText, fontSize = 14.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(accent),
            maxLines = 3
        )
    }
}

private suspend fun updateQuickTextCell(dao: FloatClipDao, rowIndex: Int, value: String) {
    var rows = dao.getQuickTextsOnce()
    while (rows.size <= rowIndex) {
        val position = (dao.maxQuickTextPosition() ?: -1) + 1
        dao.insertQuickText(QuickTextEntity(title = "", content = "", position = position))
        rows = dao.getQuickTextsOnce()
    }
    val row = rows[rowIndex]
    dao.updateQuickText(row.copy(title = value.take(40), content = value))
}

private fun parseClipboardGrid(text: String): List<List<String>> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
    if (normalized.isEmpty()) return listOf(listOf(""))
    return normalized.split('\n').map { line -> line.split('\t') }
}

private fun clipboardCell(item: ClipboardSheetEntity, column: Int): String = when (column) {
    0 -> item.colA
    1 -> item.colB
    2 -> item.colC
    else -> item.colD
}

private suspend fun updateClipboardCell(
    dao: FloatClipDao,
    rowIndex: Int,
    column: Int,
    value: String
) {
    var rows = dao.getClipboardSheetOnce()
    while (rows.size <= rowIndex) {
        val position = (dao.maxClipboardSheetPosition() ?: -1) + 1
        dao.insertClipboardSheet(ClipboardSheetEntity(position = position))
        rows = dao.getClipboardSheetOnce()
    }
    val row = rows[rowIndex]
    val updated = when (column) {
        0 -> row.copy(colA = value)
        1 -> row.copy(colB = value)
        2 -> row.copy(colC = value)
        else -> row.copy(colD = value)
    }
    dao.updateClipboardSheet(updated)
}

private fun buildCsv(headers: List<String>, rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    return (listOf(headers) + rows).joinToString("\n") { row ->
        row.joinToString(",") { value -> "\"" + value.replace("\"", "\"\"") + "\"" }
    }
}

private fun exportCsv(context: Context, sheetName: String, csv: String) {
    val fileName = "FloatClip_" + sheetName + "_" + System.currentTimeMillis() + ".csv"
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/FloatClip"
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the CSV file.")
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open the CSV file.")
            output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(csv) }
            ClipboardTools.toast(context, "CSV saved to Downloads/FloatClip.")
        } else {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val file = File(directory, fileName)
            file.writeText(csv, Charsets.UTF_8)
            ClipboardTools.toast(context, "CSV saved: " + file.absolutePath)
        }
    } catch (exception: Exception) {
        ClipboardTools.toast(context, "CSV export failed: " + (exception.message ?: "Unknown error"))
    }
}
