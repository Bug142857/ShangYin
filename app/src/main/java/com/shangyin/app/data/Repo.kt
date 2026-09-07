package com.shangyin.app.data

import android.content.Context
import androidx.room.withTransaction
import com.shangyin.app.data.db.AppDatabase
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.data.db.ItemListEntity
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
        itemDao.findByDouban(r.category.label, r.doubanId)?.let { return it.id }
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
        return if (id != -1L) id
        else itemDao.findByDouban(r.category.label, r.doubanId)?.id ?: -1L
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
                            subTitle = if (r.category == Category.BOOK)
                                freshInfo ?: existing.subTitle
                            else existing.subTitle,
                            directors = detail.directors ?: existing.directors,
                            casts = detail.casts ?: existing.casts,
                            genres = detail.genres ?: existing.genres,
                            doubanUrl = r.url ?: existing.doubanUrl
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

    /** 手动添加（游戏等豆瓣搜索不可用时的兜底） */
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
        return if (id != -1L) id else itemDao.findByDouban(categoryLabel, doubanId)?.id ?: -1L
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

    /** 获取每个分类方块的前N个条目封面，用于主页拼图 */
    suspend fun getListCovers(listId: Long, limit: Int = 4): List<String> {
        val items = getAllItemsIn(listId)
        return items.take(limit).mapNotNull { it.coverUrl }
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

    /** 获取所有已加入清单的条目 ID */
    suspend fun getAllListItemIds(): List<Long> = listDao.getAllListItemIds()

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
    }

    /** 删除所有不在任何清单中的收藏条目（清理"孤立收藏"），返回删除数量 */
    suspend fun clearOrphanItems(): Int {
        val orphanIds = listDao.getOrphanItemIds()
        if (orphanIds.isEmpty()) return 0
        orphanIds.forEach { itemDao.deleteById(it) }
        return orphanIds.size
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
