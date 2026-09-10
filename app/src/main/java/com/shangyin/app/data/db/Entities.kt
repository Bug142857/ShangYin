package com.shangyin.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "items",
    indices = [Index(value = ["category", "doubanId"], unique = true)]
)
data class CollectionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val doubanId: String,
    val title: String,
    val subTitle: String = "",
    val year: String = "",
    val doubanRating: Float? = null,
    val coverUrl: String? = null,
    val summary: String = "",
    val info: String = "",
    val directors: String = "",
    val casts: String = "",
    val genres: String = "",
    val doubanUrl: String? = null,
    val status: String = "",
    val myRating: Int = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lists")
data class ItemListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    /** 父清单 ID；null = 根级清单（首页分类方块） */
    val parentId: Long? = null,
    /** 同级排序权重：子清单拖拽排序后写入，0 = 未手动排序（按 createdAt） */
    val sortIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "list_items",
    primaryKeys = ["listId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = ItemListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CollectionItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("listId"), Index("itemId")]
)
data class ListItemEntity(
    val listId: Long,
    val itemId: Long,
    val orderIndex: Int = 0
)

/** 清单 + 条目数量（封面复用清单自身的 coverUrl，由 Repo 写入首件封面） */
data class ListWithMeta(
    @Embedded val list: ItemListEntity,
    val itemCount: Int
)

/** 清单树搜索结果：条目 + 它所属的清单 ID（用于显示"来自哪个子清单"） */
data class ItemWithOwnerList(
    @Embedded val item: CollectionItemEntity,
    val ownerListId: Long
)
