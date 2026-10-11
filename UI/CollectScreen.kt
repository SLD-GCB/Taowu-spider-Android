package com.taowu.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taowu.backend.field.CollectRow
import com.taowu.backend.field.CollectStore
import com.taowu.ui.theme.UiColors

/**
 * 采集页：文字采集的结果表。
 * 一行 = 一个页面按某模板抽出的一条记录；可查看 / 单条删 / 清空 / 导出 CSV·JSON。
 */
@Composable
fun CollectScreen() {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(CollectStore.load(context)) }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(CollectStore.toCsv(rows).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(context, "已导出 CSV", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(CollectStore.toJson(rows).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(context, "已导出 JSON", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(UiColors.bg),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("采集", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = UiColors.text)
            Text("${rows.size} 条　·　文字采集结果", color = UiColors.muted, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            TextButton(onClick = { if (rows.isNotEmpty()) csvLauncher.launch("taowu_collect.csv") }, enabled = rows.isNotEmpty()) {
                Text("导出 CSV", color = UiColors.link)
            }
            TextButton(onClick = { if (rows.isNotEmpty()) jsonLauncher.launch("taowu_collect.json") }, enabled = rows.isNotEmpty()) {
                Text("导出 JSON", color = UiColors.link)
            }
            TextButton(onClick = { rows = CollectStore.clear(context) }, enabled = rows.isNotEmpty()) {
                Text("清空", color = UiColors.error)
            }
        }

        if (rows.isEmpty()) {
            Text(
                "还没有采集结果。去「捕获」页浏览网页，点「抓正文」就会出现在这里。",
                color = UiColors.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(rows) { _, r -> CollectRowCard(r) { rows = CollectStore.remove(context, r.url) } }
            }
        }
    }
}

@Composable
private fun CollectRowCard(r: CollectRow, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = UiColors.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.template.ifBlank { "采集" }, color = UiColors.link, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("删", color = UiColors.error, fontSize = 12.sp) }
            }
            Text(r.url, color = UiColors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            for ((k, v) in r.values) {
                if (k == "job_url" || v.isBlank()) continue
                Text(
                    "$k：$v",
                    color = UiColors.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
