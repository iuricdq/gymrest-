package com.gymrest

import android.content.Context
import androidx.room.*

// ── Data model ────────────────────────────────────────────────────────────────

@Entity(tableName = "sets")
data class SetRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val exercise:      String,
    val restSec:       Int,
    val hrAtEnd:       Int,
    val timestampMs:   Long = System.currentTimeMillis()
)

@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val dateMs:        Long = System.currentTimeMillis(),
    val totalSets:     Int,
    val totalRestSec:  Int,
    val exercises:     String    // comma-separated
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface SetDao {
    @Insert suspend fun insert(set: SetRecord): Long
    @Query("SELECT * FROM sets WHERE timestampMs > :sinceMs ORDER BY timestampMs DESC")
    suspend fun since(sinceMs: Long): List<SetRecord>
    @Query("SELECT COUNT(*) FROM sets WHERE timestampMs > :sinceMs")
    suspend fun countSince(sinceMs: Long): Int
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: SessionRecord): Long
    @Query("SELECT * FROM sessions ORDER BY dateMs DESC LIMIT 30")
    suspend fun recent(): List<SessionRecord>
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(entities = [SetRecord::class, SessionRecord::class], version = 1)
abstract class GymDb : RoomDatabase() {
    abstract fun setDao(): SetDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: GymDb? = null
        fun get(ctx: Context): GymDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                GymDb::class.java,
                "gymrest.db"
            ).build().also { INSTANCE = it }
        }
    }
}

// ── Session tracker ───────────────────────────────────────────────────────────

class SessionTracker(context: Context) {
    private val db    = GymDb.get(context)
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO
    )
    private val exercises = mutableListOf<String>()
    private var totalRest = 0

    fun recordSet(exercise: String, restSec: Int, hrBpm: Int) {
        exercises += exercise
        totalRest += restSec
        scope.launch {
            db.setDao().insert(SetRecord(
                exercise    = exercise,
                restSec     = restSec,
                hrAtEnd     = hrBpm
            ))
        }
    }

    fun closeSession() {
        if (exercises.isEmpty()) return
        scope.launch {
            db.sessionDao().insert(SessionRecord(
                totalSets    = exercises.size,
                totalRestSec = totalRest,
                exercises    = exercises.distinct().joinToString(",")
            ))
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launch(
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ) = kotlinx.coroutines.launch(block = block)
}
