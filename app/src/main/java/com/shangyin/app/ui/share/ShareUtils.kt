package com.shangyin.app.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.shangyin.app.data.db.CollectionItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 图片卡片中的一行 */
private data class ShareRow(
    val cover: Bitmap?,
    val title: StaticLayout,
    val meta: StaticLayout?,
    val note: StaticLayout?
)

/** 文字清单与图片卡片分享 */
object ShareUtils {

    // ---------- 文字 ----------

    fun buildItemText(item: CollectionItemEntity): String = buildString {
        append("《${item.title}》")
        if (item.year.isNotBlank()) append("（${item.year}）")
        appendLine()
        if (item.doubanRating != null && item.doubanRating > 0f) {
            append("豆瓣 ${String.format("%.1f", item.doubanRating)}")
            if (item.myRating > 0) append(" · 我评 ${item.myRating}★")
        } else if (item.myRating > 0) {
            append("我评 ${item.myRating}★")
        }
        if (item.status.isNotBlank()) append(" · ${item.status}")
        appendLine()
        if (item.note.isNotBlank()) appendLine(item.note)
        item.doubanUrl?.let { appendLine(it) }
    }

    fun shareItemText(context: Context, item: CollectionItemEntity) {
        shareText(context, buildItemText(item), "分享《${item.title}》")
    }

    fun buildListText(listName: String, items: List<CollectionItemEntity>): String = buildString {
        appendLine("我的私藏清单「$listName」")
        appendLine("共 ${items.size} 件 · 来自「拾藏」")
        appendLine()
        items.forEachIndexed { i, item ->
            append("${i + 1}. ${item.title}")
            if (item.year.isNotBlank()) append("（${item.year}）")
            appendLine()
            val parts = mutableListOf<String>()
            if (item.doubanRating != null && item.doubanRating > 0f) {
                parts.add("豆瓣 ${String.format("%.1f", item.doubanRating)}")
            }
            if (item.myRating > 0) parts.add("我评 ${item.myRating}★")
            if (item.status.isNotBlank()) parts.add(item.status)
            if (parts.isNotEmpty()) appendLine("   " + parts.joinToString(" · "))
            if (item.note.isNotBlank()) appendLine("   ${item.note.replace("\n", " ")}")
            appendLine()
        }
    }

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    // ---------- 图片卡片 ----------

    private const val W = 1080
    private const val MARGIN = 64
    private const val COVER_W = 180
    private const val COVER_H = 252
    private const val ROW_GAP = 28

    /** 生成清单分享图，写入 cache 分享目录，失败返回 null */
    suspend fun buildListImage(
        context: Context,
        listName: String,
        items: List<CollectionItemEntity>
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val loader = ImageLoader(context)

            // 预加载封面
            val covers = items.map { item ->
                item.coverUrl?.takeIf { it.isNotBlank() }?.let { loadBitmap(loader, context, it) }
            }

            // 计算每行文字块高度
            val textW = W - MARGIN * 2 - COVER_W - 28
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1F1F1F"); textSize = 44f; typeface = Typeface.DEFAULT_BOLD
            }
            val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B45309"); textSize = 34f
            }
            val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8A8A8A"); textSize = 34f
            }

            val rows: List<Pair<ShareRow, Int>> = items.mapIndexed { i, item ->
                val title = ellipsize(item.title, 34)
                val metaText = buildString {
                    if (item.doubanRating != null && item.doubanRating > 0f) {
                        append("★ ${String.format("%.1f", item.doubanRating)} 豆瓣")
                    }
                    if (item.myRating > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("我评 ${item.myRating}★")
                    }
                    if (item.status.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(item.status)
                    }
                }.toString()
                val noteText = ellipsize(item.note.replace("\n", " "), 72)
                val titleLayout = layout(title, titlePaint, textW)
                val metaLayout = if (metaText.isBlank()) null else layout(metaText, metaPaint, textW)
                val noteLayout = if (noteText.isBlank()) null else layout(noteText, notePaint, textW)
                val textH = titleLayout.height +
                    (metaLayout?.height ?: 0) + (if (metaLayout != null) 8 else 0) +
                    (noteLayout?.height ?: 0) + (if (noteLayout != null) 8 else 0)
                val rowH = maxOf(COVER_H, textH)
                ShareRow(covers[i], titleLayout, metaLayout, noteLayout) to rowH
            }

            val headerH = 200
            val footerH = 110
            val contentH = rows.sumOf { it.second + ROW_GAP }
            val totalH = headerH + contentH + footerH

            val bitmap = Bitmap.createBitmap(W, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.parseColor("#FAF6EF"))

            // 头部
            val headerTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#14231D"); textSize = 64f; typeface = Typeface.DEFAULT_BOLD
            }
            val headerSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9B9488"); textSize = 36f
            }
            val titleLayout = layout(ellipsize(listName, 16), headerTitle, W - MARGIN * 2)
            canvas.withTranslation(MARGIN.toFloat(), 56f) { titleLayout.draw(this) }
            canvas.drawText(
                "共 ${items.size} 件收藏 · 拾藏",
                MARGIN.toFloat(), 56f + titleLayout.height + 52f, headerSub
            )

            // 行
            var y = headerH.toFloat() - 30f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            rows.forEach { (row, rowH) ->
                // 封面
                val coverTop = y + (rowH - COVER_H) / 2f
                if (row.cover != null) {
                    drawCover(canvas, row.cover, MARGIN.toFloat(), coverTop)
                } else {
                    paint.color = Color.parseColor("#E7E0D2")
                    canvas.drawRoundRect(
                        RectF(MARGIN.toFloat(), coverTop, (MARGIN + COVER_W).toFloat(), coverTop + COVER_H),
                        16f, 16f, paint
                    )
                }
                // 文字
                val tx = (MARGIN + COVER_W + 28).toFloat()
                var ty = y + 6f
                canvas.withTranslation(tx, ty) { row.title.draw(this) }
                ty += row.title.height + 8f
                row.meta?.let {
                    canvas.withTranslation(tx, ty) { it.draw(this) }
                    ty += it.height + 8f
                }
                row.note?.let {
                    canvas.withTranslation(tx, ty) { it.draw(this) }
                }
                y += rowH + ROW_GAP
            }

            // 页脚
            val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B7B0A2"); textSize = 32f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("— 由「拾藏」整理分享 —", W / 2f, (totalH - 36).toFloat(), footerPaint)

            // 保存
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "shangyin_list_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }.getOrNull()
    }

    private inline fun Canvas.withTranslation(x: Float, y: Float, block: Canvas.() -> Unit) {
        val checkpoint = save()
        translate(x, y)
        try {
            block()
        } finally {
            restoreToCount(checkpoint)
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(false)
            .build()

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    /** 封面按目标尺寸中心裁剪绘制 */
    private fun drawCover(canvas: Canvas, src: Bitmap, left: Float, top: Float) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val dstRatio = COVER_W.toFloat() / COVER_H
        val srcRatio = src.width.toFloat() / src.height
        val srcRect: Rect = if (srcRatio > dstRatio) {
            val w = (src.height * dstRatio).toInt()
            Rect((src.width - w) / 2, 0, (src.width + w) / 2, src.height)
        } else {
            val h = (src.width / dstRatio).toInt()
            Rect(0, (src.height - h) / 2, src.width, (src.height + h) / 2)
        }
        val save = canvas.save()
        canvas.clipRect(left, top, left + COVER_W, top + COVER_H)
        // 圆角
        val radii = 16f
        canvas.clipRoundRect(left, top, COVER_W.toFloat(), COVER_H.toFloat(), radii)
        canvas.drawBitmap(
            src, srcRect,
            RectF(left, top, left + COVER_W, top + COVER_H), paint
        )
        canvas.restoreToCount(save)
    }

    private fun Canvas.clipRoundRect(l: Float, t: Float, w: Float, h: Float, r: Float) {
        val path = android.graphics.Path().apply {
            addRoundRect(RectF(l, t, l + w, t + h), r, r, android.graphics.Path.Direction.CW)
        }
        clipPath(path)
    }

    private suspend fun loadBitmap(loader: ImageLoader, context: Context, url: String): Bitmap? =
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(COVER_W * 2, COVER_H * 2)
                .allowHardware(false)
                .build()
            (loader.execute(request) as? SuccessResult)?.drawable?.let { d ->
                (d as? BitmapDrawable)?.bitmap
            }
        }.getOrNull()

    // ---------- 系统分享 ----------

    fun shareImage(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享清单图片"))
    }
}
