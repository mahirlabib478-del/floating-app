package com.floatclip.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "quick_texts")
data class QuickTextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_clipboard")
data class SavedClipboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slot: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface FloatClipDao {
    @Query("SELECT * FROM quick_texts ORDER BY position ASC, id ASC")
    fun observeQuickTexts(): Flow<List<QuickTextEntity>>

    @Query("SELECT * FROM quick_texts ORDER BY position ASC, id ASC")
    suspend fun getQuickTextsOnce(): List<QuickTextEntity>

    @Query("SELECT * FROM quick_texts WHERE id = :id LIMIT 1")
    suspend fun getQuickTextById(id: Long): QuickTextEntity?

    @Query("SELECT MAX(position) FROM quick_texts")
    suspend fun maxQuickTextPosition(): Int?

    @Insert
    suspend fun insertQuickText(item: QuickTextEntity): Long

    @Update
    suspend fun updateQuickText(item: QuickTextEntity)

    @Query("UPDATE quick_texts SET position = :position WHERE id = :id")
    suspend fun updateQuickTextPosition(id: Long, position: Int)

    @Query("DELETE FROM quick_texts WHERE id = :id")
    suspend fun deleteQuickText(id: Long)

    @Query("SELECT * FROM saved_clipboard WHERE slot = :slot ORDER BY id DESC")
    fun observeSaved(slot: String): Flow<List<SavedClipboardEntity>>

    @Insert
    suspend fun insertSaved(item: SavedClipboardEntity): Long

    @Update
    suspend fun updateSaved(item: SavedClipboardEntity)

    @Query("DELETE FROM saved_clipboard WHERE id = :id")
    suspend fun deleteSaved(id: Long)

    @Query("SELECT COUNT(*) FROM saved_clipboard WHERE slot = :slot")
    suspend fun countSaved(slot: String): Int
}

@Database(
    entities = [QuickTextEntity::class, SavedClipboardEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FloatClipDatabase : RoomDatabase() {
    abstract fun dao(): FloatClipDao

    companion object {
        @Volatile
        private var instance: FloatClipDatabase? = null

        fun get(context: Context): FloatClipDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FloatClipDatabase::class.java,
                    "floatclip.db"
                ).build().also { instance = it }
            }
        }
    }
}
