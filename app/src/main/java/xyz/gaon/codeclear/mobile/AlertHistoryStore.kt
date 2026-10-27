package xyz.gaon.codeclear.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.Looper
import java.security.MessageDigest

/** App-private, credential-encrypted SQLite history. Call every operation from a worker thread. */
class AlertHistoryStore(context: Context, name: String = "alerts.db") : AutoCloseable {
    init {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,74}\\.db")))
        require(!context.isDeviceProtectedStorage)
    }

    private val helper = HistoryDatabase(context.applicationContext, name)
    private val database: SQLiteDatabase
        get() {
            check(Looper.myLooper() != Looper.getMainLooper())
            return helper.writableDatabase
        }

    fun insertBatch(captures: List<CapturedAlert>): List<Long> {
        require(captures.size in 1..40)
        val db = database
        db.beginTransactionNonExclusive()
        try {
            val ids = captures.map(::insert)
            db.setTransactionSuccessful()
            return ids
        } finally {
            db.endTransaction()
        }
    }

    fun insert(capture: CapturedAlert): Long {
        val hash = originalHash(capture.original)
        val values = ContentValues().apply {
            put("source_package", capture.sourcePackage)
            put("source_kind", capture.source.name)
            put("source_identity", capture.sourceIdentity)
            put("title", capture.title)
            put("original", capture.original)
            put("original_hash", hash)
            put("search_original", HistoryText.searchable(capture.title + "\n" + capture.original))
            put("captured_ms", capture.capturedAtMillis)
            put("posted_ms", capture.postedAtMillis)
            put("received_ms", capture.receivedAtMillis)
            put("heading_hint", capture.headingHint)
            capture.broadcast?.let {
                put("broadcast_format", it.format)
                put("broadcast_serial", it.serial)
                put("broadcast_category", it.category)
                put("broadcast_priority", it.priority)
                put("cmas_class", it.cmasClass)
                put("etws_type", it.etwsType)
            }
        }
        val db = database
        val inserted = db.insertWithOnConflict(
            "alerts",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted > 0) return inserted
        // Equality of text alone never merges two occurrences. Require the source identity too.
        require(capture.sourceIdentity != null)
        db.rawQuery(
            "SELECT id, original FROM alerts WHERE source_package=? AND source_identity=? " +
                "AND original_hash=? LIMIT 1",
            arrayOf(capture.sourcePackage, capture.sourceIdentity, hash)
        ).use {
            if (it.moveToFirst() && it.getString(1) == capture.original) return it.getLong(0)
        }
        throw SQLiteException("Unable to store captured alert")
    }

    fun page(
        query: String,
        target: String,
        beforeId: Long = Long.MAX_VALUE,
        limit: Int = 50,
        cancellation: CancellationSignal? = null
    ): List<SavedAlert> {
        require(target in HistoryText.targets && query.length <= 256 && limit in 1..50)
        val args = mutableListOf(target, beforeId.toString())
        val matching = if (query.isBlank()) {
            ""
        } else {
            val pattern = HistoryText.literalLike(query)
            args += listOf(pattern, pattern)
            " AND (a.search_original LIKE ? ESCAPE '\\' OR EXISTS " +
                "(SELECT 1 FROM translations s WHERE s.alert_id=a.id AND " +
                "s.search_text LIKE ? ESCAPE '\\'))"
        }
        args += limit.toString()
        return database.rawQuery(
            SELECT_ALERT + " WHERE a.id<?" + matching + " ORDER BY a.id DESC LIMIT ?",
            args.toTypedArray(),
            cancellation
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readAlert(cursor)) } }
    }

    fun find(id: Long, target: String): SavedAlert? {
        require(target in HistoryText.targets)
        return database.rawQuery(
            SELECT_ALERT + " WHERE a.id=? LIMIT 1",
            arrayOf(target, id.toString())
        ).use { if (it.moveToFirst()) readAlert(it) else null }
    }

    fun beginTranslation(id: Long, target: String, retry: Boolean = false): TranslationTicket? {
        require(target in HistoryText.targets)
        val db = database
        db.beginTransactionNonExclusive()
        try {
            val alert = find(id, target) ?: return null
            val previous = alert.translation
            if (!retry && previous != null && previous.phase != TranslationPhase.QUEUED) return null
            val revision = (previous?.revision ?: 0) + 1
            val values = ContentValues().apply {
                put("alert_id", id)
                put("target", target)
                put("revision", revision)
                put("phase", TranslationPhase.WORKING.name)
                putNull("text")
                putNull("source_language")
                putNull("search_text")
            }
            check(
                db.insertWithOnConflict(
                    "translations",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                ) !=
                    -1L
            )
            db.setTransactionSuccessful()
            return TranslationTicket(id, target, revision, alert.capture.original)
        } finally {
            db.endTransaction()
        }
    }

    fun finishTranslation(ticket: TranslationTicket, result: SavedTranslation): Boolean {
        require(ticket.target in HistoryText.targets)
        require(result.phase !in setOf(TranslationPhase.QUEUED, TranslationPhase.WORKING))
        require(
            result.sourceLanguage == null || result.sourceLanguage.matches(Regex("[A-Za-z-]{1,32}"))
        )
        if (result.phase == TranslationPhase.READY) {
            require(!result.text.isNullOrBlank() && result.text.length <= 16384)
        } else {
            require(result.text == null)
        }
        val values = ContentValues().apply {
            put("phase", result.phase.name)
            put("text", result.text)
            put("source_language", result.sourceLanguage)
            put("search_text", result.text?.let(HistoryText::searchable))
        }
        // Completion can update only the still-existing, still-current job. It cannot insert history.
        return database.update(
            "translations",
            values,
            "alert_id=? AND target=? AND revision=? AND phase=?",
            arrayOf(
                ticket.alertId.toString(),
                ticket.target,
                ticket.revision.toString(),
                TranslationPhase.WORKING.name
            )
        ) == 1
    }

    fun nextQueued(target: String): Long? {
        require(target in HistoryText.targets)
        return database.rawQuery(
            "SELECT a.id FROM alerts a LEFT JOIN translations t " +
                "ON t.alert_id=a.id AND t.target=? " +
                "WHERE t.phase IS NULL OR t.phase=? ORDER BY a.id DESC LIMIT 1",
            arrayOf(target, TranslationPhase.QUEUED.name)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun cachedTranslation(original: String, target: String): SavedTranslation? {
        require(original.length <= 8192 && target in HistoryText.targets)
        return database.rawQuery(
            "SELECT t.text, t.source_language FROM translations t " +
                "JOIN alerts a ON a.id=t.alert_id " +
                "WHERE a.original_hash=? AND a.original=? AND t.target=? AND t.phase=? LIMIT 1",
            arrayOf(originalHash(original), original, target, TranslationPhase.READY.name)
        ).use {
            if (it.moveToFirst()) {
                SavedTranslation(TranslationPhase.READY, it.getString(0), it.getString(1))
            } else {
                null
            }
        }
    }

    fun recoverInterruptedWork() {
        database.execSQL(
            "UPDATE translations SET phase=?, revision=revision+1 WHERE phase=?",
            arrayOf(TranslationPhase.QUEUED.name, TranslationPhase.WORKING.name)
        )
    }

    fun requeueMissingModels(target: String) {
        require(target in HistoryText.targets)
        database.execSQL(
            "UPDATE translations SET phase=?, revision=revision+1 WHERE target=? AND phase=?",
            arrayOf(TranslationPhase.QUEUED.name, target, TranslationPhase.NEEDS_MODELS.name)
        )
    }

    fun delete(id: Long) {
        database.delete("alerts", "id=?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        database.delete("alerts", null, null)
    }

    override fun close() {
        check(Looper.myLooper() != Looper.getMainLooper())
        helper.close()
    }

    private fun readAlert(cursor: Cursor): SavedAlert = with(cursor) {
        fun text(name: String): String? = getString(getColumnIndexOrThrow(name))
        fun number(name: String): Long = getLong(getColumnIndexOrThrow(name))
        fun optional(name: String): Long? = getColumnIndexOrThrow(name).let {
            if (isNull(it)) null else getLong(it)
        }
        val metadata = optional("broadcast_format")?.let {
            BroadcastMetadata(
                it.toInt(),
                number("broadcast_serial").toInt(),
                number("broadcast_category").toInt(),
                number("broadcast_priority").toInt(),
                optional("cmas_class")?.toInt(),
                optional("etws_type")?.toInt()
            )
        }
        val translated = text("translation_phase")?.let {
            SavedTranslation(
                TranslationPhase.valueOf(it),
                text("translation_text"),
                text("source_language"),
                number("translation_revision")
            )
        }
        SavedAlert(
            number("id"),
            CapturedAlert(
                sourcePackage = text("source_package")!!,
                source = CaptureSource.valueOf(text("source_kind")!!),
                title = text("title")!!,
                original = text("original")!!,
                capturedAtMillis = number("captured_ms"),
                postedAtMillis = optional("posted_ms"),
                receivedAtMillis = optional("received_ms"),
                sourceIdentity = text("source_identity"),
                headingHint = text("heading_hint")!!,
                broadcast = metadata
            ),
            translated
        )
    }

    private class HistoryDatabase(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 1) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE alerts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_package TEXT NOT NULL, source_kind TEXT NOT NULL, source_identity TEXT,
                    title TEXT NOT NULL, original TEXT NOT NULL, original_hash TEXT NOT NULL,
                    search_original TEXT NOT NULL, captured_ms INTEGER NOT NULL,
                    posted_ms INTEGER, received_ms INTEGER, heading_hint TEXT NOT NULL,
                    broadcast_format INTEGER, broadcast_serial INTEGER, broadcast_category INTEGER,
                    broadcast_priority INTEGER, cmas_class INTEGER, etws_type INTEGER,
                    UNIQUE(source_package, source_identity, original_hash)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX alert_original_hash ON alerts(original_hash)")
            db.execSQL(
                """
                CREATE TABLE translations (
                    alert_id INTEGER NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
                    target TEXT NOT NULL, revision INTEGER NOT NULL, phase TEXT NOT NULL,
                    text TEXT, source_language TEXT, search_text TEXT,
                    PRIMARY KEY(alert_id, target)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
            throw SQLiteException("A preserving history migration is required")
    }

    private companion object {
        const val SELECT_ALERT = "SELECT a.*, t.phase AS translation_phase, " +
            "t.text AS translation_text, t.source_language AS source_language, " +
            "t.revision AS translation_revision FROM alerts a LEFT JOIN translations t " +
            "ON t.alert_id=a.id AND t.target=?"

        fun originalHash(original: String): String = MessageDigest.getInstance("SHA-256")
            .digest(original.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
