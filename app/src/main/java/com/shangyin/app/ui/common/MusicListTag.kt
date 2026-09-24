package com.shangyin.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 「音乐清单」标识：音乐清单和普通清单不是一回事（固定列表布局、没有子清单、不参与拖拽排序），
 * 在清单管理 / 收藏对话框 / 首页清单行上打个小标签，一眼区分。
 */
@Composable
fun MusicListTag(modifier: Modifier = Modifier) {
    Text(
        "音乐",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}
