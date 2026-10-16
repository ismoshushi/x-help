package xyz.moshushi.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UsageSession(
    val id: Long = 0,
    val date: String,
    val openedAt: Long,
    val closedAt: Long?,
    val durationSec: Long
)

data class StatSnapshot(
    val id: Long = 0,
    val timestamp: Long,
    val followers: Int?,
    val following: Int?,
    val posts: Int?,
    val raw: String?
)

data class DailySummary(
    val date: String,
    val opens: Int,
    val totalSec: Long,
    val firstOpenAt: Long?,
    val lastCloseAt: Long?,
    val followersStart: Int?,
    val followersEnd: Int?,
    val postsStart: Int?,
    val postsEnd: Int?
)

private fun Cursor.longOrNull(col: String): Long? {
    val i = getColumnIndex(col)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

private fun Cursor.intOrNull(col: String): Int? {
    val i = getColumnIndex(col)
    return if (i < 0 || isNull(i)) null else getInt(i)
}

private fun Cursor.str(col: String): String {
    val i = getColumnIndex(col)
    return if (i < 0 || isNull(i)) "" else getString(i)
}

/** 原生 SQLite 存取：sessions / snapshots / daily_summary */
class DbHelper private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "xtracker.db", null, 2) {

    companion object {
        @Volatile
        private var inst: DbHelper? = null
        fun get(ctx: Context): DbHelper =
            inst ?: synchronized(this) {
                inst ?: DbHelper(ctx).also { inst = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, openedAt INTEGER, closedAt INTEGER, durationSec INTEGER)")
        db.execSQL("CREATE TABLE snapshots(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, followers INTEGER, following INTEGER, posts INTEGER, raw TEXT)")
        db.execSQL("CREATE TABLE daily_summary(date TEXT PRIMARY KEY, opens INTEGER, totalSec INTEGER, firstOpenAt INTEGER, lastCloseAt INTEGER, followersStart INTEGER, followersEnd INTEGER, postsStart INTEGER, postsEnd INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val now = System.currentTimeMillis()
            // v1 脏数据修复：
            // 1) 关闭孤儿会话（服务被杀/升级时没来得及关闭的），单会话按 6 小时封顶
            db.execSQL(
                "UPDATE sessions SET closedAt=$now, " +
                        "durationSec=CASE WHEN ($now - openedAt) / 1000 > 21600 THEN 21600 " +
                        "ELSE ($now - openedAt) / 1000 END " +
                        "WHERE closedAt IS NULL"
            )
            // 2) 清理抖动产生的 <5 秒会话和异常的 >6 小时会话
            db.execSQL("DELETE FROM sessions WHERE closedAt IS NOT NULL AND (durationSec < 5 OR durationSec > 21600)")
        }
    }

    /** 服务启动时关闭上次没来得及关闭的孤儿会话，返回修复条数 */
    suspend fun closeOrphanSessions(now: Long): Int = withContext(Dispatchers.IO) {
        val db = writableDatabase
        var fixed = 0
        db.query("sessions", arrayOf("id", "openedAt"), "closedAt IS NULL", null, null, null, null)
            .use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val openedAt = c.getLong(1)
                    var dur = maxOf(0L, (now - openedAt) / 1000)
                    if (dur > 21600) dur = 21600 // 6 小时封顶
                    val cv = ContentValues().apply {
                        put("closedAt", now)
                        put("durationSec", dur)
                    }
                    db.update("sessions", cv, "id=?", arrayOf(id.toString()))
                    fixed++
                }
            }
        fixed
    }

    /** 重置某日记录：清除当天的会话、快照和日报（修脏数据用） */
    suspend fun clearDay(date: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("sessions", "date=?", arrayOf(date))
        val (s, e) = xyz.moshushi.app.util.dayBounds(date)
        db.delete("snapshots", "timestamp>=? AND timestamp<?", arrayOf(s.toString(), e.toString()))
        db.delete("daily_summary", "date=?", arrayOf(date))
    }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("sessions", "id=?", arrayOf(id.toString()))
    }

    private fun rowToSession(c: Cursor) = UsageSession(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        date = c.str("date"),
        openedAt = c.getLong(c.getColumnIndexOrThrow("openedAt")),
        closedAt = c.longOrNull("closedAt"),
        durationSec = c.getLong(c.getColumnIndexOrThrow("durationSec"))
    )

    private fun rowToSnapshot(c: Cursor) = StatSnapshot(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
        followers = c.intOrNull("followers"),
        following = c.intOrNull("following"),
        posts = c.intOrNull("posts"),
        raw = c.getColumnIndex("raw").takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
    )

    private fun rowToSummary(c: Cursor) = DailySummary(
        date = c.str("date"),
        opens = c.getInt(c.getColumnIndexOrThrow("opens")),
        totalSec = c.getLong(c.getColumnIndexOrThrow("totalSec")),
        firstOpenAt = c.longOrNull("firstOpenAt"),
        lastCloseAt = c.longOrNull("lastCloseAt"),
        followersStart = c.intOrNull("followersStart"),
        followersEnd = c.intOrNull("followersEnd"),
        postsStart = c.intOrNull("postsStart"),
        postsEnd = c.intOrNull("postsEnd")
    )

    suspend fun insertSession(s: UsageSession): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("date", s.date); put("openedAt", s.openedAt); put("durationSec", s.durationSec)
        }
        writableDatabase.insert("sessions", null, cv)
    }

    suspend fun sessionById(id: Long): UsageSession? = withContext(Dispatchers.IO) {
        readableDatabase.query("sessions", null, "id=?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) rowToSession(c) else null }
    }

    suspend fun closeSession(id: Long, closedAt: Long, dur: Long) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("closedAt", closedAt); put("durationSec", dur) }
        writableDatabase.update("sessions", cv, "id=?", arrayOf(id.toString()))
    }

    suspend fun sessionsOf(date: String): List<UsageSession> = withContext(Dispatchers.IO) {
        readableDatabase.query("sessions", null, "date=?", arrayOf(date), null, null, "openedAt ASC")
            .use { c -> buildList { while (c.moveToNext()) add(rowToSession(c)) } }
    }

    suspend fun insertSnapshot(s: StatSnapshot) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("timestamp", s.timestamp)
            s.followers?.let { put("followers", it) }
            s.following?.let { put("following", it) }
            s.posts?.let { put("posts", it) }
            s.raw?.let { put("raw", it) }
        }
        writableDatabase.insert("snapshots", null, cv)
    }

    suspend fun lastSnapshot(): StatSnapshot? = withContext(Dispatchers.IO) {
        readableDatabase.query("snapshots", null, null, null, null, null, "timestamp DESC", "1")
            .use { c -> if (c.moveToFirst()) rowToSnapshot(c) else null }
    }

    suspend fun snapshotsBetween(from: Long, to: Long): List<StatSnapshot> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "snapshots", null, "timestamp>=? AND timestamp<?",
            arrayOf(from.toString(), to.toString()), null, null, "timestamp ASC"
        ).use { c -> buildList { while (c.moveToNext()) add(rowToSnapshot(c)) } }
    }

    suspend fun upsertSummary(s: DailySummary) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("date", s.date); put("opens", s.opens); put("totalSec", s.totalSec)
            s.firstOpenAt?.let { put("firstOpenAt", it) }
            s.lastCloseAt?.let { put("lastCloseAt", it) }
            s.followersStart?.let { put("followersStart", it) }
            s.followersEnd?.let { put("followersEnd", it) }
            s.postsStart?.let { put("postsStart", it) }
            s.postsEnd?.let { put("postsEnd", it) }
        }
        writableDatabase.insertWithOnConflict("daily_summary", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun recentSummaries(n: Int): List<DailySummary> = withContext(Dispatchers.IO) {
        readableDatabase.query("daily_summary", null, null, null, null, null, "date DESC", n.toString())
            .use { c -> buildList { while (c.moveToNext()) add(rowToSummary(c)) } }
    }
}
