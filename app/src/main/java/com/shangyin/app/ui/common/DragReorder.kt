package com.shangyin.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 拖拽排序手势：长按后拖动改变顺序。
 *  - 快速抬起 → tap
 *  - 长按不动 → long press（删除确认）
 *  - 长按 + 拖动 → 拖拽排序
 * 通用版：currentIdsState 为当前可排序区域的有序 ID 列表，onReorder 执行落库 */
@Composable
fun dragReorderModifier(
    itemId: Long,
    isListMode: Boolean,
    gridColumns: Int,
    currentIdsState: State<List<Long>>,
    onDragStateChange: (Long?) -> Unit,
    onReorder: suspend (fromIdx: Int, toIdx: Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    itemHeightDp: Int = 64
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val screenW = LocalConfiguration.current.screenWidthDp
    val itemHeightPx = with(density) { itemHeightDp.dp.toPx() }
    val itemWidthPx = with(density) {
        val w = (screenW - 56) / gridColumns.coerceAtLeast(1)
        w.dp.toPx()
    }

    return Modifier.pointerInput(itemId) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var state = 0 // 0=initial, 1=longPressed, 2=dragging, -1=cancelled
            var totalY = 0f
            var totalX = 0f
            val lpJob = scope.launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                state = 1
                // 长按触发时震一下，提醒用户"可以拖动了"；进入拖动阶段就不再震
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            do {
                val event = awaitPointerEvent()
                val c = event.changes.first()
                val dy = c.positionChange().y
                val dx = c.positionChange().x

                if (state == 0) {
                    if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                        lpJob.cancel()
                        state = -1
                    }
                } else if (state == 1) {
                    if (abs(dy) > viewConfiguration.touchSlop / 2 || abs(dx) > viewConfiguration.touchSlop / 2) {
                        state = 2
                        onDragStateChange(itemId)
                        totalY = 0f
                        totalX = 0f
                        c.consume()
                    }
                }

                if (state == 2) {
                    c.consume()
                    totalY += dy
                    totalX += dx
                    val cols = if (isListMode) 1 else gridColumns

                    // 垂直拖动 → 跨行交换
                    if (abs(totalY) > itemHeightPx * 0.5f) {
                        val dir = if (totalY > 0) 1 else -1
                        val idx = currentIdsState.value.indexOf(itemId)
                        if (idx >= 0) {
                            val target = idx + dir * cols
                            if (target in currentIdsState.value.indices) {
                                scope.launch { onReorder(idx, target) }
                                totalY -= dir * itemHeightPx
                            } else {
                                totalY = 0f
                            }
                        }
                    }
                    // 水平拖动（仅网格模式）→ 同行交换
                    if (!isListMode && abs(totalX) > itemWidthPx * 0.5f) {
                        val dir = if (totalX > 0) 1 else -1
                        val idx = currentIdsState.value.indexOf(itemId)
                        if (idx >= 0) {
                            val target = idx + dir
                            if (target in currentIdsState.value.indices && idx / cols == target / cols) {
                                scope.launch { onReorder(idx, target) }
                                totalX -= dir * itemWidthPx
                            } else {
                                totalX = 0f
                            }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })

            lpJob.cancel()
            when (state) {
                0 -> onTap()           // 快速抬起 → 点击
                1 -> onLongPress()     // 长按不动 → 删除确认
                2 -> onDragStateChange(null) // 拖拽结束
                // -1: 移动取消（滚动），不触发任何操作
            }
        }
    }
}
