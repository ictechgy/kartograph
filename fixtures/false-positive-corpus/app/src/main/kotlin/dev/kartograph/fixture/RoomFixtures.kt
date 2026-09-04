package dev.kartograph.fixture

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity
data class CorpusEntity(@PrimaryKey val id: Long)

@Dao
interface CorpusDao {
    @Query("SELECT * FROM CorpusEntity")
    fun all(): List<CorpusEntity>
}

@Database(entities = [CorpusEntity::class], version = 1, exportSchema = false)
abstract class CorpusDatabase : RoomDatabase() {
    abstract fun corpusDao(): CorpusDao
}
