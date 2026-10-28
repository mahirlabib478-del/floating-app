package com.floatclip.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.floatclip.app.data.FloatClipDao
import com.floatclip.app.data.QuickTextEntity
import com.floatclip.app.data.SavedClipboardEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val GridLine = Color(0xFFB8C2C7)
private val GridText = Color(0xFF202124)
private val RowHeaderPink = Color(0xFFE91E63)

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
private val columnWidths = listOf(150.dp, 150.dp, 150.dp, 150.dp)

@Composable
fun QuickTextSpreadsheet(items: List<QuickTextEntity>, dao: FloatClipDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    val editMutex = remember { Mutex() }
    var paletteIndex by remember { mutableStateOf(0) }
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
            onCopyColumn = { column ->
                val values = items.map { quickCell(it, column) }
                if (values.none { it.isNotBlank() }) {
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " is empty.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip " + columnNames[column], values.joinToString("\n"))
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " copied.")
                }
            },
            onCsv = {
                val csv = buildCsv(
                    items.filter { it.title.isNotBlank() || it.content.isNotBlank() || it.colC.isNotBlank() || it.colD.isNotBlank() }
                        .map { listOf(it.title, it.content, it.colC, it.colD) }
                )
                if (csv.isBlank()) {
                    ClipboardTools.toast(context, "No records to export.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip Quick Text CSV", csv)
                    ClipboardTools.toast(context, "CSV copied to clipboard.")
                }
            },
            onPalette = { paletteIndex = (paletteIndex + 1) % sheetPalettes.size }
        )

        PurgeAllButton(onClick = { showPurgeDialog = true })

        SpreadsheetHeader(
            horizontalState = horizontalState,
            palette = palette
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            key = { it }
        ) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    SpreadsheetRowNumber(index + 1, palette.rowHeader)
                    Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                        for (column in 0..3) {
                            SpreadsheetCell(
                                rowIndex = index,
                                columnIndex = column,
                                value = quickCell(item, column),
                                width = columnWidths[column],
                                accent = palette.columns[column],
                                onValueChange = { value ->
                                    scope.launch {
                                        editMutex.withLock {
                                            updateQuickCell(dao, index, column, value)
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
            dismissButton = {
                TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SavedClipboardSpreadsheet(
    slot: String,
    items: List<SavedClipboardEntity>,
    dao: FloatClipDao
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    val editMutex = remember { Mutex() }
    var paletteIndex by remember { mutableStateOf(0) }
    var showPurgeDialog by remember { mutableStateOf(false) }
    val palette = sheetPalettes[paletteIndex]
    val rowCount = maxOf(16, items.size + 1)
    val rows = (0 until rowCount).map { index ->
        items.getOrNull(index) ?: SavedClipboardEntity(
            id = -(index + 1L),
            slot = slot,
            content = "",
            position = index
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        SheetToolbar(
            palette = palette,
            onCopyColumn = { column ->
                val values = items.map { savedCell(it, column) }
                if (values.none { it.isNotBlank() }) {
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " is empty.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip " + slot + " " + columnNames[column], values.joinToString("\n"))
                    ClipboardTools.toast(context, "Column " + columnNames[column].last() + " copied.")
                }
            },
            onCsv = {
                val csv = buildCsv(
                    items.filter { it.content.isNotBlank() || it.colB.isNotBlank() || it.colC.isNotBlank() || it.colD.isNotBlank() }
                        .map { listOf(it.content, it.colB, it.colC, it.colD) }
                )
                if (csv.isBlank()) {
                    ClipboardTools.toast(context, "No records to export.")
                } else {
                    ClipboardTools.copyText(context, "FloatClip " + slot + " CSV", csv)
                    ClipboardTools.toast(context, "CSV copied to clipboard.")
                }
            },
            onPalette = { paletteIndex = (paletteIndex + 1) % sheetPalettes.size }
        )

        PurgeAllButton(onClick = { showPurgeDialog = true })

        SpreadsheetHeader(
            horizontalState = horizontalState,
            palette = palette
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    SpreadsheetRowNumber(index + 1, palette.rowHeader)
                    Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                        for (column in 0..3) {
                            SpreadsheetCell(
                                rowIndex = index,
                                columnIndex = column,
                                value = savedCell(item, column),
                                width = columnWidths[column],
                                accent = palette.columns[column],
                                onValueChange = { value ->
                                    scope.launch {
                                        editMutex.withLock {
                                            updateSavedCell(dao, slot, index, column, value)
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
            text = { Text("This permanently deletes every row in the " + slot + " Sheet. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeDialog = false
                    scope.launch {
                        dao.deleteAllSaved(slot)
                        ClipboardTools.toast(context, "All " + slot + " Sheet records deleted.")
                    }
                }) { Text("Purge all") }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SheetToolbar(
    palette: SheetPalette,
    onCopyColumn: (Int) -> Unit,
    onCsv: () -> Unit,
    onPalette: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (column in 0..3) {
            Button(
                onClick = { onCopyColumn(column) },
                modifier = Modifier.width(72.dp).height(40.dp),
                shape = RoundedCornerShape(5.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 0.dp),
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
            modifier = Modifier.width(56.dp).height(40.dp),
            shape = RoundedCornerShape(5.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE91E63),
                contentColor = Color.White
            )
        ) { Text("CSV", fontSize = 12.sp) }

        Button(
            onClick = onPalette,
            modifier = Modifier.width(44.dp).height(40.dp),
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
    palette: SheetPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(42.dp).height(46.dp)
                .background(palette.rowHeader)
                .border(1.dp, GridLine),
            contentAlignment = Alignment.Center
        ) {
            Text("#", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Row(modifier = Modifier.horizontalScroll(horizontalState)) {
            for (column in 0..3) {
                Box(
                    modifier = Modifier.width(columnWidths[column]).height(46.dp)
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
    onValueChange: (String) -> Unit
) {
    var localText by remember(rowIndex, columnIndex) { mutableStateOf(value) }
    var isFocused by remember(rowIndex, columnIndex) { mutableStateOf(false) }

    LaunchedEffect(value, isFocused) {
        if (!isFocused && localText != value) localText = value
    }

    Box(
        modifier = Modifier.width(width).height(56.dp)
            .background(Color.White)
            .border(1.dp, GridLine)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = localText,
            onValueChange = {
                localText = it
                onValueChange(it)
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
            textStyle = TextStyle(
                color = GridText,
                fontSize = 14.sp,
                lineHeight = 18.sp
            ),
            cursorBrush = SolidColor(accent),
            maxLines = 3
        )
    }
}

private fun quickCell(item: QuickTextEntity, column: Int): String = when (column) {
    0 -> item.title
    1 -> item.content
    2 -> item.colC
    else -> item.colD
}

private fun savedCell(item: SavedClipboardEntity, column: Int): String = when (column) {
    0 -> item.content
    1 -> item.colB
    2 -> item.colC
    else -> item.colD
}

private suspend fun updateQuickCell(
    dao: FloatClipDao,
    rowIndex: Int,
    column: Int,
    value: String
) {
    var rows = dao.getQuickTextsOnce()
    while (rows.size <= rowIndex) {
        val position = (dao.maxQuickTextPosition() ?: -1) + 1
        dao.insertQuickText(
            QuickTextEntity(title = "", content = "", position = position)
        )
        rows = dao.getQuickTextsOnce()
    }
    val row = rows[rowIndex]
    val updated = when (column) {
        0 -> row.copy(title = value)
        1 -> row.copy(content = value)
        2 -> row.copy(colC = value)
        else -> row.copy(colD = value)
    }
    dao.updateQuickText(updated)
}

private suspend fun updateSavedCell(
    dao: FloatClipDao,
    slot: String,
    rowIndex: Int,
    column: Int,
    value: String
) {
    var rows = dao.getSavedOnce(slot)
    while (rows.size <= rowIndex) {
        val position = (dao.maxSavedPosition(slot) ?: -1) + 1
        dao.insertSaved(
            SavedClipboardEntity(slot = slot, content = "", position = position)
        )
        rows = dao.getSavedOnce(slot)
    }
    val row = rows[rowIndex]
    val updated = when (column) {
        0 -> row.copy(content = value)
        1 -> row.copy(colB = value)
        2 -> row.copy(colC = value)
        else -> row.copy(colD = value)
    }
    dao.updateSaved(updated)
}

private fun buildCsv(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val allRows = listOf(columnNames) + rows
    return allRows.joinToString("\n") { row ->
        row.joinToString(",") { value ->
            "\"" + value.replace("\"", "\"\"") + "\""
        }
    }
}
