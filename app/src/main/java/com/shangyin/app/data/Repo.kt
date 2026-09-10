package com.shangyin.app.data

import android.content.Context
import androidx.room.withTransaction
import com.shangyin.app.data.db.AppDatabase
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.data.db.ItemListEntity
import com.shangyin.app.data.db.ItemWithOwnerList
import com.shangyin.app.data.db.ListItemEntity
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.data.douban.DoubanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/** 应用内统一的仓库入口（单例，个人自用无需 DI 框架） */
object Repo {

    private lateinit var db: AppDatabase
    private val itemDao get() = db.itemDao()
    private val listDao get() = db.listDao()

    fun init(context: Context) {
        db = AppDatabase.build(context.applicationContext)
    }

    // ---------- 条目 ----------

    fun observeItems(category: String?): Flow<List<CollectionItemEntity>> =
        itemDao.observeAll(category)

    fun observeItem(id: Long): Flow<CollectionItemEntity?> = itemDao.observeById(id)

    /** 快速收藏：只落库搜索结果自带信息，不阻塞等详情网络请求（详情页打开后自动补全） */
    suspend fun saveFromDoubanFast(r: DoubanResult): Long {
        itemDao.findByDouban(r.category.label, r.doubanId)?.let { existing ->
            ensureItemInCategoryList(existing.id, r.category.label)
            return existing.id
        }
        val id = itemDao.insert(
            CollectionItemEntity(
                category = r.category.label,
                doubanId = r.doubanId,
                title = r.title,
                subTitle = r.subTitle,
                year = r.year,
                doubanRating = r.rating,
                coverUrl = r.coverUrl,
                summary = r.intro,
                info = "",
                directors = "",
                casts = "",
                genres = "",
                doubanUrl = r.url ?: detailDefaultUrl(r),
                status = ""
            )
        )
        val finalId = if (id != -1L) id
        else itemDao.findByDouban(r.category.label, r.doubanId)?.id ?: -1L
        if (finalId != -1L) ensureItemInCategoryList(finalId, r.category.label)
        return finalId
    }

    /**
     * 确保条目归属分类同名根清单（不存在则创建）。
     * 这是"零孤儿"机制的核心：任何条目创建/收藏路径都必须落到清单里，
     * 不在任何清单中的条目会被自动清理（pruneOrphans）。
     */
    suspend fun ensureItemInCategoryList(itemId: Long, categoryLabel: String) {
        val target = listDao.observeAllLists().first()
            .firstOrNull { it.parentId == null && it.name == categoryLabel }
            ?: run {
                val newId = createList(categoryLabel)
                listDao.observeList(newId).first() ?: ItemListEntity(id = newId, name = categoryLabel)
            }
        // 已在清单内则忽略（insertItem 有唯一约束）
        val already = listDao.observeItemsIn(target.id).first().any { it.id == itemId }
        if (!already) addItemToList(target.id, itemId)
    }

    /** 删除不再属于任何清单的条目（零孤儿机制），返回删除数量 */
    suspend fun pruneOrphans(): Int {
        val orphanIds = listDao.getOrphanItemIds()
        if (orphanIds.isEmpty()) return 0
        db.withTransaction {
            orphanIds.forEach { itemDao.deleteById(it) }
        }
        return orphanIds.size
    }

    /** 从豆瓣搜索结果收藏（自动抓取详情补全封面/评分/简介/导演/演员），返回条目 id */
    suspend fun saveFromDouban(r: DoubanResult): Long {
        itemDao.findByDouban(r.category.label, r.doubanId)?.let { existing ->
            // 已有记录但导演演员为空，或日期行只有年份 → 重新抓取补充/升级
            val monthDayRe = Regex("""\d{4}[-/年.]\d{1,2}""")
            val dateLine = if (r.category == Category.BOOK) existing.subTitle else existing.info
            val stale = dateLine.isNotBlank() && !monthDayRe.containsMatchIn(dateLine)
            if ((existing.directors.isBlank() && existing.casts.isBlank()) || stale) {
                runCatching {
                    val detail = DoubanClient.fetchDetail(r.category, r.doubanId)
                    if (!detail.isEmpty) {
                        val freshInfo = detail.info?.takeIf { it.isNotBlank() }
                        itemDao.update(existing.copy(
                            title = detail.title ?: existing.title,
                            doubanRating = detail.rating ?: existing.doubanRating,
                            coverUrl = detail.coverUrl ?: existing.coverUrl,
                            summary = detail.summary ?: existing.summary,
                            info = if (r.category == Category.BOOK)
                                existing.info.ifBlank { freshInfo.orEmpty() }
                            else freshInfo ?: existing.info,
                            // 图书/游戏头部副标题同步含完整日期（从搜索来的 subTitle 本来不含日期）
                            subTitle = if (r.category == Category.GAME || r.category == Category.BOOK)
                                freshInfo ?: existing.subTitle else existing.subTitle,
                            directors = detail.directors ?: existing.directors,
                        ))
                    }
                }
            }
            return existing.id
        }
        val detail = DoubanClient.fetchDetail(r.category, r.doubanId)
        val freshInfo = detail.info?.takeIf { it.isNotBlank() }
        val entity = CollectionItemEntity(
            category = r.category.label,
            doubanId = r.doubanId,
            title = detail.title ?: r.title,
            // 图书不显示基本信息块：完整信息（含出版日期）放头部 subTitle
            subTitle = if (r.category == Category.BOOK) freshInfo ?: r.subTitle else r.subTitle,
            year = r.year,
            doubanRating = detail.rating ?: r.rating,
            coverUrl = detail.coverUrl ?: r.coverUrl,
            summary = detail.summary.orEmpty(),
            info = detail.info.orEmpty(),
            directors = detail.directors.orEmpty(),
            casts = detail.casts.orEmpty(),
            genres = detail.genres.orEmpty(),
            doubanUrl = r.url ?: detailDefaultUrl(r),
            status = ""
        )
        val id = itemDao.insert(entity)
        return if (id != -1L) id
        else itemDao.findByDouban(r.category.label, r.doubanId)?.id ?: -1L
    }

    /**
     * 收藏嗅探到的歌曲（自动归入"音乐"根清单）。
     * 同名同歌手视为同一首：已存在时若新直链不同则刷新直链（旧直链过期后的恢复手段）。
     */
    suspend fun saveMusic(
        name: String,
        artist: String,
        coverUrl: String?,
        playUrl: String?
    ): Long {
        // 清单内查重：同名同歌手视为同一首；直链变了就刷新（"音乐"清单由 addManual 自动保证存在）
        val musicList = listDao.observeAllLists().first()
            .firstOrNull { it.parentId == null && it.name == "音乐" }
        if (musicList != null) {
            val existing = listDao.observeItemsIn(musicList.id).first()
                .firstOrNull { it.title == name && it.subTitle == artist }
            if (existing != null) {
                if (!playUrl.isNullOrBlank() && playUrl != existing.doubanUrl) {
                    itemDao.update(existing.copy(doubanUrl = playUrl, updatedAt = System.currentTimeMillis()))
                }
                ensureItemInCategoryList(existing.id, "音乐")
                return existing.id
            }
        }
        return addManual(
            categoryLabel = "音乐",
            title = name,
            subTitle = artist,
            coverUrl = coverUrl,
            doubanUrl = playUrl
        )
    }

    /** 手动添加（游戏等豆瓣搜索不可用时的兜底）；自动归入分类同名根清单 */
    suspend fun addManual(
        categoryLabel: String,
        title: String,
        year: String = "",
        subTitle: String = "",
        coverUrl: String? = null,
        summary: String = "",
        doubanUrl: String? = null
    ): Long {
        val parsed = doubanUrl?.let { DoubanClient.parseDoubanUrl(it) }
        val doubanId = parsed?.second ?: "manual-${UUID.randomUUID()}"
        itemDao.findByDouban(categoryLabel, doubanId)?.let { return it.id }
        val entity = CollectionItemEntity(
            category = categoryLabel,
            doubanId = doubanId,
            title = title,
            subTitle = subTitle,
            year = year,
            coverUrl = coverUrl,
            summary = summary,
            doubanUrl = doubanUrl,
            status = ""
        )
        val id = itemDao.insert(entity)
        val finalId = if (id != -1L) id else itemDao.findByDouban(categoryLabel, doubanId)?.id ?: -1L
        if (finalId != -1L) ensureItemInCategoryList(finalId, categoryLabel)
        return finalId
    }

    private fun detailDefaultUrl(r: DoubanResult): String = when (r.category) {
        Category.BOOK -> "https://book.douban.com/subject/${r.doubanId}/"
        Category.GAME -> "https://www.douban.com/game/${r.doubanId}/"
        else -> "https://movie.douban.com/subject/${r.doubanId}/"
    }

    suspend fun updateItem(item: CollectionItemEntity) =
        itemDao.update(item.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteItem(item: CollectionItemEntity) = itemDao.delete(item)

    // ---------- 清单 ----------

    fun observeListsWithMeta(): Flow<List<ListWithMeta>> = listDao.observeListsWithMeta()

    /** 首页用：只显示根级清单（parentId IS NULL） */
    fun observeRootListsWithMeta(): Flow<List<ListWithMeta>> = listDao.observeRootListsWithMeta()

    /** 显示某清单的子清单 */
    fun observeSubListsWithMeta(listId: Long): Flow<List<ListWithMeta>> = listDao.observeSubListsWithMeta(listId)

    /** 获取每个分类方块的前N个条目封面（按"最近加入清单"顺序，跨所有子层级） */
    suspend fun getListCovers(listId: Long, limit: Int = 4): List<String> {
        // BFS 收集所有层级的 listId（用一次性查询避免每层跑递归 CTE）
        val allListIds = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.addLast(listId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in allListIds) continue
            allListIds.add(id)
            val subs = listDao.getSubListsOnce(id)
            subs.forEach { queue.addLast(it.id) }
        }
        // 按 list_items.rowid DESC 取 = 最近加入清单的条目在前，新增封面有新鲜感
        val items = if (allListIds.isEmpty()) emptyList() else listDao.getItemsInByAddedDesc(allListIds.toList())
        val covers = mutableListOf<String>()
        for (it in items) {
            it.coverUrl?.takeIf { c -> c.isNotBlank() && c !in covers }?.let { covers.add(it) }
            if (covers.size >= limit) break
        }
        return covers.take(limit)
    }

    /** 如果清单本身没有条目，取子清单里第一个有封面的条目作为封面 */
    suspend fun getFallbackCoverFromChildren(listId: Long): String? {
        val subs = listDao.getSubListsOnce(listId)
        for (sub in subs) {
            // 先看子清单自身的 coverUrl
            sub.coverUrl?.takeIf { it.isNotBlank() }?.let { return it }
            // 再看子清单里的条目封面
            val items = listDao.observeItemsIn(sub.id).first()
            val cover = items.firstOrNull()?.coverUrl?.takeIf { it.isNotBlank() }
            if (cover != null) return cover
        }
        return null
    }

    fun observeList(id: Long): Flow<ItemListEntity?> = listDao.observeList(id)

    fun observeItemsIn(listId: Long): Flow<List<CollectionItemEntity>> = listDao.observeItemsIn(listId)

    /** 获取分类下所有条目（一次性，用于删除分类清理 + 主页封面拼图） */
    suspend fun getAllItemsIn(listId: Long): List<CollectionItemEntity> =
        listDao.observeItemsIn(listId).first()

    fun observeMemberships(itemId: Long): Flow<List<Long>> = listDao.observeMemberships(itemId)

    fun observeAllLists(): Flow<List<ItemListEntity>> = listDao.observeAllLists()

    suspend fun createList(name: String, parentId: Long? = null): Long =
        listDao.insertList(ItemListEntity(name = name.trim(), parentId = parentId))

    suspend fun renameList(list: ItemListEntity, name: String) =
        listDao.updateList(list.copy(name = name.trim()))

    suspend fun deleteList(list: ItemListEntity) = listDao.deleteList(list)

    /** 删除清单并递归删除所有层级的子清单（list_items 由外键 CASCADE 自动清理），随后清理孤儿条目 */
    suspend fun deleteListTree(list: ItemListEntity) {
        db.withTransaction {
            // BFS 收集所有后代清单
            val toDelete = mutableListOf<ItemListEntity>()
            val queue = ArrayDeque<Long>()
            queue.add(list.id)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val children = listDao.getSubListsOnce(id)
                toDelete.addAll(children)
                children.forEach { queue.add(it.id) }
            }
            // lists 表无自引用外键，逐层先删子再删父即可
            toDelete.forEach { listDao.deleteList(it) }
            listDao.deleteList(list)
        }
        // 零孤儿机制：随清单删除后不再属于任何清单的条目一并清理
        pruneOrphans()
    }

    /** 子清单数量（用于删除确认提示） */
    suspend fun countSubLists(id: Long): Int = listDao.countSubLists(id)

    /** 清单内搜索：本清单 + 所有层级子清单的条目（关键字过滤由调用方做，便于显示所属清单名） */
    suspend fun searchItemsInTree(rootId: Long): List<ItemWithOwnerList> =
        listDao.searchItemsInTree(rootId)

    /** 子清单拖拽排序：把 fromIdx 的子清单移到 toIdx，重排 sortIndex */
    suspend fun reorderSubList(parentId: Long, fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx) return
        db.withTransaction {
            val subs = listDao.getSubListsOnce(parentId).toMutableList()
            if (fromIdx !in subs.indices || toIdx !in subs.indices) return@withTransaction
            val moved = subs.removeAt(fromIdx)
            subs.add(toIdx, moved)
            subs.forEachIndexed { i, sub ->
                if (sub.sortIndex != i) listDao.updateList(sub.copy(sortIndex = i))
            }
        }
    }

    /** 加入清单：若已在清单内则忽略；同时用清单首图做清单封面 */
    suspend fun addItemToList(listId: Long, itemId: Long) {
        db.withTransaction {
            val order = (listDao.maxOrder(listId) ?: -1) + 1
            val ok = listDao.insertItem(ListItemEntity(listId, itemId, order)) != -1L
            if (ok) {
                itemDao.getById(itemId)?.coverUrl?.takeIf { it.isNotBlank() }?.let { cover ->
                    // 清单封面仅在未设置时写入
                    listDao.getByIdOnce(listId)?.let { l ->
                        if (l.coverUrl.isNullOrBlank()) listDao.updateList(l.copy(coverUrl = cover))
                    }
                }
            }
        }
    }

    suspend fun removeItemFromList(listId: Long, itemId: Long) {
        listDao.removeItem(listId, itemId)
        // 零孤儿机制：条目若不再属于任何清单则直接删除
        pruneOrphans()
    }

    /** 上移/下移：delta = -1 上移，+1 下移 */
    suspend fun moveItemInList(listId: Long, itemId: Long, delta: Int) {
        db.withTransaction {
            val order = listDao.getOrder(listId)
            val idx = order.indexOfFirst { it.itemId == itemId }
            if (idx < 0) return@withTransaction
            val target = idx + delta
            if (target !in order.indices) return@withTransaction
            val a = order[idx]
            val b = order[target]
            listDao.updateItem(a.copy(orderIndex = b.orderIndex))
            listDao.updateItem(b.copy(orderIndex = a.orderIndex))
        }
    }

    /** 拖拽排序：把 fromIdx 的条目移到 toIdx，中间条目顺延 */
    suspend fun reorderItem(listId: Long, fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx) return
        db.withTransaction {
            val order = listDao.getOrder(listId).toMutableList()
            if (fromIdx !in order.indices || toIdx !in order.indices) return@withTransaction
            val item = order.removeAt(fromIdx)
            order.add(toIdx, item)
            order.forEachIndexed { i, li ->
                if (li.orderIndex != i) {
                    listDao.updateItem(li.copy(orderIndex = i))
                }
            }
        }
    }

    // ---------- 导入导出 ----------

    /** 导出全部数据 */
    suspend fun exportAll(): ExportData = ExportData(
        items = itemDao.getAllSync(),
        lists = listDao.getAllListsSync(),
        listItems = listDao.getAllListItemsSync()
    )

    /** 导入全部数据（清空后全量替换） */
    suspend fun importAll(data: ExportData) {
        db.withTransaction {
            itemDao.deleteAll()
            listDao.deleteAllLists() // CASCADE 自动清 list_items
            itemDao.insertAll(data.items)
            listDao.insertAllLists(data.lists)
            listDao.insertAllListItems(data.listItems)
        }
    }
}

/** 导出数据包 */
data class ExportData(
    val items: List<com.shangyin.app.data.db.CollectionItemEntity>,
    val lists: List<com.shangyin.app.data.db.ItemListEntity>,
    val listItems: List<com.shangyin.app.data.db.ListItemEntity>
)
