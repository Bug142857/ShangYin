package com.shangyin.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 通用页码条：[←] [1] … [cur±w] … [N] [→] + 跳页输入。
 * lastPage <= 0 表示末页未知（站点不暴露总页数）：只显示相邻窗口，右箭头始终可用。
 * v0.304 起供 番号 / 游戏 等模块复用（吃瓜/X站/P站原有实现保持不变）。
 */
@Composable
fun AppPageBar(
    currentPage: Int,
    lastPage: Int,
    loading: Boolean,
    onGo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showJump by remember { mutableStateOf(false) }
    val half = if (lastPage >= 1000) 1 else 2
    val nums = buildList {
        if (lastPage > 0) {
            val s = (currentPage - half).coerceAtLeast(1)
            val e = (currentPage + half).coerceAtMost(lastPage)
            if (s > 1) {
                add(1)
                if (s > 2) add(-1) // -1 = 省略号
            }
            for (p in s..e) add(p)
            if (e < lastPage) {
                if (e < lastPage - 1) add(-1)
                add(lastPage)
            }
        } else {
            for (d in -half..half) {
                val p = currentPage + d
                if (p >= 1) add(p)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        val backEnabled = currentPage > 1 && !loading
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = backEnabled) { onGo(currentPage - 1) }
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "上一页",
                tint = if (backEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            nums.forEach { p ->
                when {
                    p == -1 -> Text(
                        "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !loading) { showJump = true }
                            .padding(horizontal = 5.dp, vertical = 8.dp)
                    )
                    p == currentPage -> Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            "$p",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                    else -> Text(
                        "$p",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !loading) { onGo(p) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
        val fwdEnabled = !loading && (lastPage <= 0 || currentPage < lastPage)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = fwdEnabled) { onGo(currentPage + 1) }
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "下一页",
                tint = if (fwdEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        }
    }

    if (showJump) {
        var jumpText by remember { mutableStateOf("") }
        val jump = jumpText.toIntOrNull() ?: 0
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("跳转到指定页") },
            text = {
                Column {
                    if (lastPage > 0) {
                        Text(
                            "共 $lastPage 页，输入 1 ~ $lastPage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jumpText,
                        onValueChange = { jumpText = it.filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("页码") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = jump >= 1 && (lastPage <= 0 || jump <= lastPage),
                    onClick = {
                        showJump = false
                        onGo(jump)
                    }
                ) { Text("跳转") }
            },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("取消") } }
        )
    }
}
