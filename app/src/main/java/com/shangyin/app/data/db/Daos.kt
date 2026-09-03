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

    @Query(
        "SELECT l.id, l.name, l.description, l.coverUrl, l.createdAt, COUNT(li.itemId) AS itemCount " +
            "FROM lists l LEFT JOIN list_items li ON li.listId = l.id " +
            "GROUP BY l.id ORDER BY l.createdAt DESC"
    )
    fun observeListsWithMeta(): Flow<List<ListWithMeta>>

    @Query(
        "SELECT items.* FROM list_items JOIN items ON items.id = list_items.itemId " +
            "WHERE list_items.listId = :listId ORDER BY list_items.orderIndex ASC"
    )
    fun observeItemsIn(listId: Long): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY orderIndex ASC")
    suspend fun getOrder(listId: Long): List<ListItemEntity>

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
}
