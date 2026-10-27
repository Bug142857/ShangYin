package com.shangyin.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 车机 / 平板横屏适配（v0.322）：按屏幕可用宽度选择网格列数。
 * 手机竖屏保持原有列数；横屏/车机宽屏自动增加列数，避免海报和卡片被拉得过大。
 */

/** 详情 / 海报类网格列数（portrait 为手机竖屏原列数，通常 3 或 2） */
@Composable
fun adaptiveGridColumns(portrait: Int = 3): Int {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 1600 -> if (portrait <= 2) 6 else 8
        w >= 1200 -> if (portrait <= 2) 5 else 7
        w >= 900 -> if (portrait <= 2) 4 else 6
        w >= 700 -> if (portrait <= 2) 3 else 5
        w >= 600 -> if (portrait <= 2) 3 else 4
        else -> portrait
    }
}

/** 首页 / 模块管理宫格列数：车机宽屏一行放更多模块 */
@Composable
fun adaptiveModuleColumns(): Int {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 1600 -> 8
        w >= 1200 -> 7
        w >= 900 -> 6
        w >= 700 -> 5
        w >= 600 -> 4
        else -> 3
    }
}

/** 是否为宽屏（车机 / 平板）：宽度 ≥ 600dp 时设置页等可改为双栏 */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
