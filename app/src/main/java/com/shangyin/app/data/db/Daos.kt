package com.shangyin.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query(
        "SELECT * FROM items " +
            "WHERE (:category IS NULL OR category = :category) " +
            "ORDER BY updatedAt DESC"
    )
    fun observeAll(category: String?): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id")
    fun observeById(id: Long): Flow<CollectionItemEntity?>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: Long): CollectionItemEntity?

    @Query("SELECT * FROM items WHERE category = :category AND doubanId = :doubanId LIMIT 1")
    suspend fun findByDouban(category: String, doubanId: String): CollectionItemEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CollectionItemEntity): Long

    @Update
    suspend fun update(item: CollectionItemEntity)

    @Delete
    suspend fun delete(item: CollectionItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 批量改分类名（用于分类重命名 / 旧数据迁移） */
    @Query("UPDATE items SET category = :newName WHERE category = :oldName")
    suspend fun renameCategory(oldName: String, newName: String)

    /** 导出用：一次性拿所有条目 */
    @Query("SELECT * FROM items")
    suspend fun getAllSync(): List<CollectionItemEntity>

    /** 导入用：清空所有条目 */
    @Query("DELETE FROM items")
    suspend fun deleteAll()

    /** 导入用：批量插入（保留原始 ID） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CollectionItemEntity>)
}

@Dao
interface ListDao {

    @Insert
    suspend fun insertList(list: ItemListEntity): Long

    @Update
    suspend fun updateList(list: ItemListEntity)

    @Delete
    suspend fun deleteList(list: ItemListEntity)

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observeList(id: Long): Flow<ItemListEntity?>

    @Query("SELECT * FROM lists WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: Long): ItemListEntity?

    // 递归 CTE：收集某清单及其所有层级后代清单的 ID，
    // 用于统计包含子清单内所有条目的总数

    @Query(
        "SELECT l.id, l.name, l.description, l.coverUrl, l.parentId, l.sortIndex, l.createdAt, " +
            "(WITH RECURSIVE descendants(id) AS (" +
            "  SELECT id FROM lists WHERE id = l.id " +
            "  UNION ALL " +
            "  SELECT child.id FROM lists child JOIN descendants d ON child.parentId = d.id " +
            ") SELECT COUNT(*) FROM list_items li WHERE li.listId IN (SELECT id FROM descendants)" +
            ") AS itemCount " +
            "FROM lists l " +
            "WHERE l.parentId IS NULL " +
            "ORDER BY l.sortIndex ASC, l.createdAt ASC"
    )
    fun observeRootListsWithMeta(): Flow<List<ListWithMeta>>

    @Query(
        "SELECT * FROM lists WHERE parentId IS NULL ORDER BY sortIndex ASC, createdAt ASC"
    )
    suspend fun getRootListsOnce(): List<ItemListEntity>

    @Query(
        "SELECT l.id, l.name, l.description, l.coverUrl, l.parentId, l.sortIndex, l.createdAt, " +
            "(WITH RECURSIVE descendants(id) AS (" +
            "  SELECT id FROM lists WHERE id = l.id " +
            "  UNION ALL " +
            "  SELECT child.id FROM lists child JOIN descendants d ON child.parentId = d.id " +
            ") SELECT COUNT(*) FROM list_items li WHERE li.listId IN (SELECT id FROM descendants)" +
            ") AS itemCount " +
            "FROM lists l " +
            "WHERE l.parentId = :parentId " +
            "ORDER BY l.sortIndex ASC, l.createdAt ASC"
    )
    fun observeSubListsWithMeta(parentId: Long): Flow<List<ListWithMeta>>

    /** 旧方法：拿所有清单（含子清单，用于设置页分类管理） */
    @Query(
        "SELECT l.id, l.name, l.description, l.coverUrl, l.parentId, l.sortIndex, l.createdAt, " +
            "(WITH RECURSIVE descendants(id) AS (" +
            "  SELECT id FROM lists WHERE id = l.id " +
            "  UNION ALL " +
            "  SELECT child.id FROM lists child JOIN descendants d ON child.parentId = d.id " +
            ") SELECT COUNT(*) FROM list_items li WHERE li.listId IN (SELECT id FROM descendants)" +
            ") AS itemCount " +
            "FROM lists l " +
            "GROUP BY l.id ORDER BY l.createdAt DESC"
    )
    fun observeListsWithMeta(): Flow<List<ListWithMeta>>

    /** 一次性拿某父清单下的子清单（按展示顺序），用于拖拽排序 */
    @Query("SELECT * FROM lists WHERE parentId = :parentId ORDER BY sortIndex ASC, createdAt ASC")
    suspend fun getSubListsOnce(parentId: Long): List<ItemListEntity>

    /** 统计某清单下直接子清单数量 */
    @Query("SELECT COUNT(*) FROM lists WHERE parentId = :id")
    suspend fun countSubLists(id: Long): Int

    @Query(
        "SELECT items.* FROM list_items JOIN items ON items.id = list_items.itemId " +
            "WHERE list_items.listId = :listId ORDER BY list_items.orderIndex ASC"
    )
    fun observeItemsIn(listId: Long): Flow<List<CollectionItemEntity>>

    /** 清单内搜索：递归收集该清单及所有层级后代清单里的条目，并带所属清单 ID */
    @Query(
        "SELECT items.*, list_items.listId AS ownerListId FROM list_items " +
            "JOIN items ON items.id = list_items.itemId " +
            "WHERE list_items.listId IN (" +
            "WITH RECURSIVE descendants(id) AS (" +
            "  SELECT id FROM lists WHERE id = :rootId " +
            "  UNION ALL " +
            "  SELECT child.id FROM lists child JOIN descendants d ON child.parentId = d.id " +
            ") SELECT id FROM descendants) " +
            "ORDER BY list_items.orderIndex ASC"
    )
    suspend fun searchItemsInTree(rootId: Long): List<ItemWithOwnerList>

    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY orderIndex ASC")
    suspend fun getOrder(listId: Long): List<ListItemEntity>

    /** 按加入清单顺序取条目（list_items 隐式 rowid 倒序 = 最近加入在前） */
    @Query(
        "SELECT items.* FROM list_items JOIN items ON items.id = list_items.itemId " +
            "WHERE list_items.listId IN (:listIds) ORDER BY list_items.rowid DESC"
    )
    suspend fun getItemsInByAddedDesc(listIds: List<Long>): List<CollectionItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(crossRef: ListItemEntity): Long

    @Update
    suspend fun updateItem(crossRef: ListItemEntity)

    @Query("DELETE FROM list_items WHERE listId = :listId AND itemId = :itemId")
    suspend fun removeItem(listId: Long, itemId: Long)

    @Query("SELECT MAX(orderIndex) FROM list_items WHERE listId = :listId")
    suspend fun maxOrder(listId: Long): Int?

    @Query("SELECT listId FROM list_items WHERE itemId = :itemId")
    fun observeMemberships(itemId: Long): Flow<List<Long>>

    @Query("SELECT * FROM lists")
    fun observeAllLists(): Flow<List<ItemListEntity>>

    /** 导出用：一次性拿所有清单 */
    @Query("SELECT * FROM lists")
    suspend fun getAllListsSync(): List<ItemListEntity>

    /** 导出用：一次性拿所有清单-条目关联 */
    @Query("SELECT * FROM list_items")
    suspend fun getAllListItemsSync(): List<ListItemEntity>

    /** 导入用：清空所有清单（list_items 由外键 CASCADE 自动清） */
    @Query("DELETE FROM lists")
    suspend fun deleteAllLists()

    /** 导入用：批量插入清单和关联 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLists(lists: List<ItemListEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllListItems(items: List<ListItemEntity>)

    /** 获取不在任何清单中的条目 ID（清理孤立收藏） */
    @Query("SELECT i.id FROM items i WHERE i.id NOT IN (SELECT itemId FROM list_items)")
    suspend fun getOrphanItemIds(): List<Long>
}
