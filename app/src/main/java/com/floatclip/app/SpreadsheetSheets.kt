package com.floatclip.app

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.floatclip.app.data.FloatClipDao
import com.floatclip.app.data.QuickTextEntity
import com.floatclip.app.data.SavedClipboardEntity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private val GridLine = Color(0xFFDADCE0)
private val GridHeaderColor = Color(0xFFF1F3F4)
private val GridSelected = Color(0xFFE8F0FE)
private val GridSelectedBorder = Color(0xFF1A73E8)
private val GridText = Color(0xFF202124)

@Composable
fun QuickTextSpreadsheet(items: List<QuickTextEntity>, dao: FloatClipDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val horizontalState = rememberScrollState()
    var selectedRowId by remember { mutableStateOf<Long?>(null) }
    var selectedColumn by remember { mutableStateOf("B") }
    var formulaValue by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<QuickTextEntity?>(null) }

    val selectedIndex = items.indexOfFirst { it.id == selectedRowId }
    val selectedItem = items.firstOrNull { it.id == selectedRowId }
    val selectedAddress = if (selectedIndex >= 0) selectedColumn + (selectedIndex + 1).toString() else "—"
    val canEdit = selectedItem != null && selectedColumn in setOf("A", "B")

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Quick Text", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    items.size.toString() + " rows · tap a cell to edit",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = {
                scope.launch {
                    val position = (dao.maxQuickTextPosition() ?: -1) + 1
                    val id = dao.insertQuickText(
                        QuickTextEntity(
                            title = "Text " + (items.size + 1),
                            content = "",
                            position = position
                        )
                    )
                    selectedRowId = id
                    selectedColumn = "B"
                    formulaValue = ""
                }
            }) {
                Text("+ Row")
            }
        }

        FormulaBar(
            address = selectedAddress,
            value = formulaValue,
            enabled = canEdit,
            onValueChange = { formulaValue = it },
            onSave = {
                val item = selectedItem ?: return@FormulaBar
                scope.launch {
                    if (selectedColumn == "A") {
                        dao.updateQuickText(item.copy(title = formulaValue))
                    } else {
                        dao.updateQuickText(item.copy(content = formulaValue))
                    }
                    ClipboardTools.toast(context, "Cell " + selectedAddress + " saved.")
                }
            }
        )

        GridHeader(
            horizontalState = horizontalState,
            columns = listOf(
                GridColumn("A · Title", 170.dp),
                GridColumn("B · Text", 300.dp),
                GridColumn("C · Copy", 90.dp),
                GridColumn("D · Delete", 96.dp)
            )
        )

        if (items.isEmpty()) {
            GridEmptyState("No rows yet", "Tap + Row to create your first Quick Text.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        RowNumber(index + 1)
                        Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                            GridCell(
                                text = item.title,
                                width = 170.dp,
                                selected = selectedRowId == item.id && selectedColumn == "A",
                                onClick = {
                                    selectedRowId = item.id
                                    selectedColumn = "A"
                                    formulaValue = item.title
                                }
                            )
                            GridCell(
                                text = item.content,
                                width = 300.dp,
                                selected = selectedRowId == item.id && selectedColumn == "B",
                                onClick = {
                                    selectedRowId = item.id
                                    selectedColumn = "B"
                                    formulaValue = item.content
                                }
                            )
                            GridActionCell("Copy", 90.dp, Color(0xFFE8F0FE)) {
                                ClipboardTools.copyText(context, item.title, item.content)
                                ClipboardTools.toast(context, "Copied: " + item.title)
                            }
                            GridActionCell("Delete", 96.dp, Color(0xFFFCE8E6)) {
                                pendingDelete = item
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete row?") },
            text = { Text("Delete “" + item.title + "” from Quick Text?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (selectedRowId == item.id) selectedRowId = null
                    scope.launch { dao.deleteQuickText(item.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
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
    var selectedRowId by remember { mutableStateOf<Long?>(null) }
    var formulaValue by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedClipboardEntity?>(null) }

    val selectedIndex = items.indexOfFirst { it.id == selectedRowId }
    val selectedItem = items.firstOrNull { it.id == selectedRowId }
    val selectedAddress = if (selectedIndex >= 0) "A" + (selectedIndex + 1).toString() else "—"

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(slot + " Sheet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    items.size.toString() + " rows · old rows are kept",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = {
                val text = ClipboardTools.readText(context)
                if (text.isNullOrBlank()) {
                    ClipboardTools.toast(context, "Clipboard is empty. Copy text first.")
                } else {
                    scope.launch {
                        val id = dao.insertSaved(SavedClipboardEntity(slot = slot, content = text))
                        selectedRowId = id
                        formulaValue = text
                        ClipboardTools.toast(context, "Added a new row to " + slot + " Sheet.")
                    }
                }
            }) {
                Text("+ Paste")
            }
        }

        FormulaBar(
            address = selectedAddress,
            value = formulaValue,
            enabled = selectedItem != null,
            onValueChange = { formulaValue = it },
            onSave = {
                val item = selectedItem ?: return@FormulaBar
                scope.launch {
                    dao.updateSaved(item.copy(content = formulaValue))
                    ClipboardTools.toast(context, "Cell " + selectedAddress + " saved.")
                }
            }
        )

        GridHeader(
            horizontalState = horizontalState,
            columns = listOf(
                GridColumn("A · Clipboard text", 300.dp),
                GridColumn("B · Added", 160.dp),
                GridColumn("C · Copy", 90.dp),
                GridColumn("D · Delete", 96.dp)
            )
        )

        if (items.isEmpty()) {
            GridEmptyState(
                "No rows yet",
                "Copy text in another app, then tap + Paste or use the floating bubble."
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        RowNumber(index + 1)
                        Row(modifier = Modifier.horizontalScroll(horizontalState)) {
                            GridCell(
                                text = item.content,
                                width = 300.dp,
                                selected = selectedRowId == item.id,
                                onClick = {
                                    selectedRowId = item.id
                                    formulaValue = item.content
                                }
                            )
                            GridCell(
                                text = DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT
                                ).format(Date(item.createdAt)),
                                width = 160.dp,
                                readOnly = true
                            )
                            GridActionCell("Copy", 90.dp, Color(0xFFE8F0FE)) {
                                ClipboardTools.copyText(context, slot + " saved text", item.content)
                                ClipboardTools.toast(context, "Copied from " + slot + " Sheet.")
                            }
                            GridActionCell("Delete", 96.dp, Color(0xFFFCE8E6)) {
                                pendingDelete = item
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete row?") },
            text = { Text("This row will be removed from " + slot + " Sheet.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (selectedRowId == item.id) selectedRowId = null
                    scope.launch { dao.deleteSaved(item.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private data class GridColumn(val title: String, val width: Dp)

@Composable
private fun FormulaBar(
    address: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.width(44.dp).height(48.dp)
                .border(1.dp, GridLine, RoundedCornerShape(4.dp))
                .background(GridHeaderColor, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(address, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GridText)
        }
        Text("fx", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5F6368))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Select a cell") },
            textStyle = MaterialTheme.typography.bodySmall
        )
        Button(enabled = enabled, onClick = onSave) {
            Text("Save")
        }
    }
}

@Composable
private fun GridHeader(
    horizontalState: androidx.compose.foundation.ScrollState,
    columns: List<GridColumn>
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(GridHeaderColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(44.dp).height(42.dp).border(1.dp, GridLine),
            contentAlignment = Alignment.Center
        ) {
            Text("#", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GridText)
        }
        Row(modifier = Modifier.horizontalScroll(horizontalState)) {
            columns.forEach { column ->
                Box(
                    modifier = Modifier.width(column.width).height(42.dp)
                        .border(1.dp, GridLine)
                        .background(GridHeaderColor)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        column.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GridText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RowNumber(number: Int) {
    Box(
        modifier = Modifier.width(44.dp).heightIn(min = 48.dp)
            .border(1.dp, GridLine)
            .background(GridHeaderColor)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(number.toString(), fontSize = 12.sp, color = Color(0xFF5F6368))
    }
}

@Composable
private fun GridCell(
    text: String,
    width: Dp,
    selected: Boolean = false,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val background = if (selected) GridSelected else Color.White
    val cellModifier = Modifier.width(width).heightIn(min = 48.dp)
        .background(background)
        .border(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) GridSelectedBorder else GridLine
        )
        .then(if (onClick != null && !readOnly) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 9.dp, vertical = 10.dp)

    Box(modifier = cellModifier, contentAlignment = Alignment.CenterStart) {
        Text(
            text = text.ifEmpty { " " },
            fontSize = 13.sp,
            color = GridText,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GridActionCell(
    label: String,
    width: Dp,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.width(width).heightIn(min = 48.dp)
            .border(1.dp, GridLine)
            .background(Color.White)
            .clickable { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().background(tint, RoundedCornerShape(4.dp))
                .padding(vertical = 8.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GridText)
        }
    }
}

@Composable
private fun GridEmptyState(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}
