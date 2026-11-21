package com.jet.downloader.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val formatLabel: String,
    val filePath: String,
    val fileSize: String,
    val mediaType: String,
    @ColumnInfo(defaultValue = "''") val thumbnailUrl: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
abstract class JetDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}
