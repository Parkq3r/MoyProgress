package ru.moyprogress.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.moyprogress.domain.*

@Entity(tableName = "training_state")
data class StoredTraining(@PrimaryKey val id: String, val payload: String)

@Dao
interface TrainingDao {
    @Query("SELECT * FROM training_state")
    fun observe(): kotlinx.coroutines.flow.Flow<List<StoredTraining>>

    @Query("SELECT * FROM training_state")
    suspend fun read(): List<StoredTraining>

    @Upsert
    suspend fun upsert(records: List<StoredTraining>)

    @Delete
    suspend fun delete(records: List<StoredTraining>)
}

@Database(entities = [StoredTraining::class], version = 2, exportSchema = false)
abstract class TrainingDatabase : RoomDatabase() {
    abstract fun training(): TrainingDao
}

private val sessionRowsMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE training_state RENAME TO training_state_legacy")
        db.execSQL("CREATE TABLE training_state (id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("INSERT INTO training_state (id, payload) SELECT 'legacy', payload FROM training_state_legacy WHERE id = 1")
        db.execSQL("DROP TABLE training_state_legacy")
    }
}

@Serializable
private data class Backup(val version: Int, val data: TrainingData)

class TrainingRepository(context: Context) {
    private val database = Room.databaseBuilder(
        context, TrainingDatabase::class.java, "moy-progress.db"
    ).addMigrations(sessionRowsMigration).build()
    private val dao = database.training()
    private val json = Json { encodeDefaults = true }

    val data = dao.observe().map(::decode).flowOn(Dispatchers.Default)

    suspend fun initialize() {
        database.withTransaction {
            val records = dao.read()
            if (records.none { it.id == "metadata" }) {
                store(records, decode(records) ?: initialData(LocalDate.now().toEpochDay()))
            }
        }
    }

    suspend fun change(transform: (TrainingData) -> TrainingData) {
        database.withTransaction {
            val records = dao.read()
            val current = decode(records) ?: initialData(LocalDate.now().toEpochDay())
            val next = transform(current)
            if (next != current) store(records, next)
        }
    }

    suspend fun export(): String {
        val data = decode(dao.read()) ?: error("Дневник ещё не открыт.")
        return json.encodeToString(Backup(1, data))
    }

    suspend fun import(text: String) {
        val backup = try {
            json.decodeFromString<Backup>(text)
        } catch (_: IllegalArgumentException) {
            error("Файл не удалось прочитать. Выберите резервную копию «Мой прогресс».")
        }
        require(backup.version == 1) { "Эта версия резервной копии не поддерживается." }
        require(valid(backup.data)) { "В резервной копии есть недопустимые данные." }
        change { backup.data }
    }

    private fun decode(records: List<StoredTraining>): TrainingData? {
        val metadata = records.firstOrNull { it.id == "metadata" }
            ?: return records.firstOrNull { it.id == "legacy" }
                ?.let { json.decodeFromString<Backup>(it.payload).data }
        val data = json.decodeFromString<TrainingData>(metadata.payload)
        val sessions = records.asSequence().filter { it.id.startsWith("session:") }
            .map { json.decodeFromString<Session>(it.payload) }
            .sortedWith(compareBy<Session> { it.day }.thenBy { it.finishedAt }).toList()
        return data.copy(sessions = sessions)
    }

    private suspend fun store(previous: List<StoredTraining>, data: TrainingData) {
        val next = buildList {
            add(StoredTraining("metadata", json.encodeToString(data.copy(sessions = emptyList()))))
            data.sessions.forEach { session ->
                add(StoredTraining("session:${session.id}", json.encodeToString(session)))
            }
        }.associateBy { it.id }
        val before = previous.associateBy { it.id }
        val removed = previous.filter { it.id !in next }
        val changed = next.values.filter { before[it.id] != it }
        if (removed.isNotEmpty()) dao.delete(removed)
        if (changed.isNotEmpty()) dao.upsert(changed)
    }
}

private fun valid(data: TrainingData): Boolean {
    fun <T> unique(items: List<T>): Boolean = items.distinct().size == items.size
    fun validDate(day: Long) = day in -365_000L..365_000L
    fun validName(name: String) = name.isNotBlank() && name.length <= 80
    fun validExercise(exercise: Exercise) = validName(exercise.name) &&
        (exercise.baseline == null || exercise.baseline in 1..exercise.limit)
    fun validGoal(goal: Goal, limit: Int) = goal.low in 1..limit && goal.high in goal.low..limit

    val exercises = data.exercises.associateBy { it.id }
    if (data.exercises.isEmpty() || exercises.size != data.exercises.size ||
        !data.exercises.all(::validExercise) || data.active == null ||
        !unique(data.programs.map { it.id }) || !unique(data.sessions.map { it.id }) ||
        data.sessions.count { it.finishedAt == null } > 1) return false

    val validPrograms = data.programs.all { plan ->
        validName(plan.name) && validDate(plan.start) && plan.weeks in 1..52 &&
            unique(plan.workouts.map { it.id }) && plan.workouts.all { day ->
                validName(day.name) && day.weekday in 1..7 &&
                    unique(day.exercises.map { it.exerciseId }) && day.exercises.all targets@{ target ->
                        val limit = exercises[target.exerciseId]?.limit ?: return@targets false
                        target.goals.size in 1..20 && target.goals.all { validGoal(it, limit) }
                    }
            }
    }
    val validSessions = data.sessions.all { session ->
        val timer = session.stopwatch
        val timerLog = session.logs.firstOrNull { it.exercise.id == timer?.exerciseId }
        validDate(session.day) && validDate(session.slot) && session.week in 1..52 &&
            validName(session.label) && session.logs.isNotEmpty() &&
            unique(session.logs.map { it.exercise.id }) &&
            (timer == null || (timerLog != null && timerLog.exercise.timed &&
                timer.setIndex in timerLog.sets.indices && (!timer.sideTwo || timerLog.exercise.sided))) &&
            session.logs.all { log ->
                val limit = log.exercise.limit
                validExercise(log.exercise) && (log.effort == null || log.effort in 1..10) &&
                    log.sets.size in 1..20 && log.sets.all {
                        validGoal(it.goal, limit) && it.value in 0..limit && it.second in 0..limit &&
                            (!it.done || (it.value > 0 && (!log.exercise.sided || it.second > 0)))
                    }
            }
    }
    val validProposals = unique(data.proposals.map { it.id }) && data.proposals.all { proposal ->
        val exercise = exercises[proposal.exerciseId]
        exercise != null && proposal.setIndex in proposal.before.indices && proposal.step in 1..5 &&
            proposal.before.all { validGoal(it, exercise.limit) } &&
            increasedGoal(proposal.before[proposal.setIndex], proposal.step).high <= exercise.limit
    }
    return validPrograms && validSessions && validProposals
}
