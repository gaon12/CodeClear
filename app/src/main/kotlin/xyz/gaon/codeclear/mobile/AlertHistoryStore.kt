package xyz.gaon.codeclear.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.database.sqlite.transaction
import xyz.gaon.codeclear.sha256Hex
import xyz.gaon.codeclear.translation.StandardTranslationAttribution
import xyz.gaon.codeclear.translation.StandardTranslationCatalog
import xyz.gaon.codeclear.translation.TranslationEngineKind

/** App-private, credential-encrypted SQLite history. Call every operation from a worker thread. */
class AlertHistoryStore(
    context: Context,
    name: String = "alerts.db",
    private val maxPeerAlerts: Int = 1000
) : AutoCloseable {
    init {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,74}\\.db")))
        require(!context.isDeviceProtectedStorage)
        require(maxPeerAlerts in 1..1000)
    }

    private val helper = HistoryDatabase(context.applicationContext, name)
    private val database: SQLiteDatabase
        get() {
            check(Looper.myLooper() != Looper.getMainLooper())
            return helper.writableDatabase
        }

    fun insertBatch(captures: List<CapturedAlert>): List<Long?> {
        require(captures.size in 1..40)
        val db = database
        return db.transaction(exclusive = false) {
            captures.map { insertLocked(it)?.id }
        }
    }

    /** A null result means this exact source observation was recently deleted by the owner. */
    fun insert(capture: CapturedAlert): Long? = insertObservation(capture)?.id

    fun insertObservation(capture: CapturedAlert): StoredObservation? {
        val db = database
        return db.transaction(exclusive = false) {
            insertLocked(capture)
        }
    }

    private fun insertLocked(capture: CapturedAlert): StoredObservation? {
        val hash = originalHash(capture.original)
        val db = database
        capture.sourceIdentity?.let { identity ->
            db.rawQuery(
                "SELECT 1 FROM ignored_sources WHERE fingerprint=? AND deleted_ms>=? LIMIT 1",
                arrayOf(
                    sourceFingerprint(capture.sourcePackage, identity, hash),
                    (System.currentTimeMillis() - IGNORE_LIFETIME_MILLIS).toString()
                )
            ).use { if (it.moveToFirst()) return null }
        }
        if (capture.source == CaptureSource.PEER) {
            require(
                capture.original.isNotBlank() && capture.original.length <= 8192 &&
                    capture.title.length <= 256
            )
            require(capture.sourceIdentity?.matches(Regex("[0-9a-f]{64}")) == true)
            db.rawQuery(
                "SELECT id FROM alerts WHERE source_package=? AND source_identity=? " +
                    "AND original_hash=? LIMIT 1",
                arrayOf(capture.sourcePackage, capture.sourceIdentity, hash)
            ).use { if (it.moveToFirst()) return StoredObservation(it.getLong(0), created = false) }
            db.rawQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM alerts WHERE source_kind='PEER' LIMIT ?)",
                arrayOf(maxPeerAlerts.toString())
            ).use { if (it.moveToFirst() && it.getInt(0) >= maxPeerAlerts) return null }
        }
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
            put("reception_context", capture.context.encode())
            capture.broadcast?.let {
                put("broadcast_format", it.format)
                put("broadcast_serial", it.serial)
                put("broadcast_category", it.category)
                put("broadcast_priority", it.priority)
                put("cmas_class", it.cmasClass)
                put("etws_type", it.etwsType)
            }
        }
        val inserted = db.insertWithOnConflict(
            "alerts",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted > 0) {
            val leader = AlertObservationGroups.assign(db, inserted, capture, hash)
            return StoredObservation(leader, created = leader == inserted, observationId = inserted)
        }
        // An existing source observation requires both its identity and original text.
        require(capture.sourceIdentity != null)
        db.rawQuery(
            "SELECT id, original FROM alerts WHERE source_package=? AND source_identity=? " +
                "AND original_hash=? LIMIT 1",
            arrayOf(capture.sourcePackage, capture.sourceIdentity, hash)
        ).use {
            if (it.moveToFirst() && it.getString(1) == capture.original) {
                return StoredObservation(
                    resolveId(it.getLong(0))!!,
                    created = false,
                    observationId = it.getLong(0)
                )
            }
        }
        throw SQLiteException("Unable to store captured alert")
    }

    fun page(
        query: String,
        target: String,
        beforeId: Long = Long.MAX_VALUE,
        limit: Int = 50,
        cancellation: CancellationSignal? = null,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT,
        nativeOnly: Boolean = false,
        groupIdenticalOriginals: Boolean = false
    ): List<SavedAlert> {
        require(target in HistoryText.targets && query.length <= 256 && limit in 1..50)
        val args = mutableListOf(target, engine.key, beforeId.toString())
        val matching = if (query.isBlank()) {
            ""
        } else {
            val pattern = HistoryText.literalLike(query)
            args += listOf(pattern, pattern)
            if (groupIdenticalOriginals) {
                " AND EXISTS (SELECT 1 FROM alerts n WHERE ($CONTENT_FAMILY) AND " +
                    "(n.search_original LIKE ? ESCAPE '\\' OR EXISTS " +
                    "(SELECT 1 FROM translations s WHERE s.alert_id=n.id AND " +
                    "s.search_text LIKE ? ESCAPE '\\')))"
            } else {
                " AND (a.search_original LIKE ? ESCAPE '\\' OR EXISTS " +
                    "(SELECT 1 FROM translations s WHERE s.alert_id=a.id AND " +
                    "s.search_text LIKE ? ESCAPE '\\'))"
            }
        }
        args += limit.toString()
        return database.rawQuery(
            selectAlert(groupIdenticalOriginals) + " WHERE a.display_group_id IS NULL AND a.id<?" +
                (if (nativeOnly) " AND a.source_kind!='PEER'" else "") +
                (
                    if (groupIdenticalOriginals) {
                        " AND NOT EXISTS (SELECT 1 FROM alerts n " +
                            "WHERE n.display_group_id IS NULL " +
                            "AND n.id>a.id AND ($CONTENT_FAMILY))"
                    } else {
                        ""
                    }
                    ) +
                matching + " ORDER BY a.id DESC LIMIT ?",
            args.toTypedArray(),
            cancellation
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readAlert(cursor)) } }
    }

    fun find(
        id: Long,
        target: String,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT,
        withObservations: Boolean = false,
        observationBeforeId: Long = Long.MAX_VALUE,
        cancellation: CancellationSignal? = null
    ): SavedAlert? {
        require(target in HistoryText.targets)
        require(observationBeforeId > 0)
        val alert = database.rawQuery(
            selectAlert(withObservations) +
                " WHERE a.id=(SELECT COALESCE(display_group_id,id) FROM alerts WHERE id=?) LIMIT 1",
            arrayOf(target, engine.key, id.toString()),
            cancellation
        ).use { if (it.moveToFirst()) readAlert(it) else null } ?: return null
        if (!withObservations) return alert
        val family = contentFamily(alert.id) ?: return alert
        val observations = database.rawQuery(
            "SELECT id,source_kind,captured_ms,posted_ms,received_ms FROM alerts " +
                "WHERE (${family.where}) AND id<? ORDER BY id DESC LIMIT 21",
            family.args + observationBeforeId.toString(),
            cancellation
        ).use { cursor ->
            buildList {
                fun optional(index: Int): Long? =
                    if (cursor.isNull(index)) null else cursor.getLong(index)
                while (cursor.moveToNext()) {
                    add(
                        HistoryObservation(
                            cursor.getLong(0),
                            CaptureSource.valueOf(cursor.getString(1)),
                            cursor.getLong(2),
                            optional(3),
                            optional(4)
                        )
                    )
                }
            }
        }
        return alert.copy(
            observations = observations.take(20),
            moreObservations =
                observations.size > 20
        )
    }

    private fun resolveId(id: Long): Long? = database.rawQuery(
        "SELECT COALESCE(display_group_id,id) FROM alerts WHERE id=? LIMIT 1",
        arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun updateHeading(id: Long, hint: String) {
        require(hint.matches(Regex("[A-Z_]{1,40}")))
        database.update(
            "alerts",
            ContentValues().apply { put("heading_hint", hint) },
            "id=?",
            arrayOf(id.toString())
        )
    }

    fun beginTranslation(
        id: Long,
        target: String,
        retry: Boolean = false,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
    ): TranslationTicket? {
        require(target in HistoryText.targets)
        val db = database
        return db.transaction(exclusive = false) {
            val alert = find(id, target, engine) ?: return@transaction null
            val canonicalId = alert.id
            val previous = alert.translation
            if (!retry && previous != null &&
                previous.phase != TranslationPhase.QUEUED
            ) {
                return@transaction null
            }
            val revision = (previous?.revision ?: 0) + 1
            val values = ContentValues().apply {
                put("alert_id", canonicalId)
                put("target", target)
                put("engine", engine.key)
                put("same_language", 0)
                put("revision", revision)
                put("phase", TranslationPhase.WORKING.name)
                putNull("text")
                putNull("source_language")
                putNull("search_text")
                putNull("template_id")
                putNull("catalog_revision")
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
            TranslationTicket(canonicalId, target, revision, alert.capture.original, engine)
        }
    }

    fun queueTranslation(
        id: Long,
        target: String,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
    ) {
        require(target in HistoryText.targets)
        database.execSQL(
            "INSERT OR IGNORE INTO translations(alert_id,target,engine,revision,phase) " +
                "SELECT COALESCE(display_group_id,id),?,?,0,? FROM alerts WHERE id=?",
            arrayOf<Any>(target, engine.key, TranslationPhase.QUEUED.name, id)
        )
    }

    /** Persist a failed record's retry without replacing a live ticket or an existing result. */
    fun queueRetry(id: Long, target: String, engine: TranslationEngineKind): Boolean {
        require(target in HistoryText.targets)
        val db = database
        return db.transaction(exclusive = false) {
            val alert = find(id, target, engine) ?: return@transaction false
            val previous = alert.translation
            if (previous?.phase in setOf(TranslationPhase.READY, TranslationPhase.WORKING)) {
                return@transaction false
            }
            if (previous == null) {
                queueTranslation(alert.id, target, engine)
            } else if (previous.phase != TranslationPhase.QUEUED) {
                val changed = db.update(
                    "translations",
                    ContentValues().apply {
                        put("phase", TranslationPhase.QUEUED.name)
                        put("revision", previous.revision + 1)
                        put("same_language", 0)
                        putNull("text")
                        putNull("source_language")
                        putNull("search_text")
                        putNull("template_id")
                        putNull("catalog_revision")
                    },
                    "alert_id=? AND target=? AND engine=?",
                    arrayOf(alert.id.toString(), target, engine.key)
                )
                check(changed == 1)
            }
            true
        }
    }

    fun finishTranslation(ticket: TranslationTicket, result: SavedTranslation): Boolean {
        require(ticket.target in HistoryText.targets)
        require(
            result.phase !in
                setOf(TranslationPhase.QUEUED, TranslationPhase.WORKING, TranslationPhase.STALE)
        )
        if (ticket.engine != result.engine) return false
        if (result.standard != null &&
            (
                result.phase != TranslationPhase.READY ||
                    !StandardTranslationCatalog.isCurrent(result.standard)
                )
        ) {
            return false
        }
        require(
            result.sourceLanguage == null ||
                (
                    result.sourceLanguage.length <= 32 &&
                        result.sourceLanguage.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))
                    )
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
            put("same_language", result.sameLanguage)
            put("template_id", result.standard?.templateId)
            put("catalog_revision", result.standard?.catalogRevision)
            put("search_text", result.text?.let(HistoryText::searchable))
        }
        // Complete only the still-existing current job; never insert history from a response.
        return database.update(
            "translations",
            values,
            "alert_id=? AND target=? AND engine=? AND revision=? AND phase=?",
            arrayOf(
                ticket.alertId.toString(),
                ticket.target,
                ticket.engine.key,
                ticket.revision.toString(),
                TranslationPhase.WORKING.name
            )
        ) == 1
    }

    fun nextQueued(
        target: String,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
    ): Long? {
        require(target in HistoryText.targets)
        return database.rawQuery(
            "SELECT t.alert_id FROM translations t JOIN alerts a ON a.id=t.alert_id " +
                "WHERE t.target=? AND t.engine=? AND t.phase=? AND a.display_group_id IS NULL " +
                "ORDER BY t.alert_id DESC LIMIT 1",
            arrayOf(target, engine.key, TranslationPhase.QUEUED.name)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun cachedTranslation(
        original: String,
        target: String,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
    ): SavedTranslation? {
        require(original.length <= 8192 && target in HistoryText.targets)
        return database.rawQuery(
            "SELECT t.text, t.source_language, t.same_language, t.template_id, " +
                "t.catalog_revision FROM translations t " +
                "JOIN alerts a ON a.id=t.alert_id " +
                "WHERE a.original_hash=? AND a.original=? AND t.target=? AND t.engine=? " +
                "AND t.phase=? AND (t.template_id IS NULL OR t.catalog_revision=?) LIMIT 1",
            arrayOf(
                originalHash(original),
                original,
                target,
                engine.key,
                TranslationPhase.READY.name,
                StandardTranslationCatalog.REVISION
            )
        ).use {
            if (it.moveToFirst()) {
                SavedTranslation(
                    TranslationPhase.READY,
                    it.getString(0),
                    it.getString(1),
                    engine = engine,
                    sameLanguage = it.getInt(2) != 0,
                    standard = it.getString(3)?.let { id ->
                        StandardTranslationAttribution(id, it.getString(4))
                    }
                )
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

    fun requeueMissingModels(
        target: String? = null,
        engine: TranslationEngineKind = TranslationEngineKind.ML_KIT
    ) {
        require(target == null || target in HistoryText.targets)
        val arguments = mutableListOf<Any>(
            TranslationPhase.QUEUED.name,
            engine.key,
            TranslationPhase.NEEDS_MODELS.name
        )
        if (target != null) arguments += target
        database.execSQL(
            "UPDATE translations SET phase=?, revision=revision+1 " +
                "WHERE engine=? AND phase=?" + if (target != null) " AND target=?" else "",
            arguments.toTypedArray()
        )
    }

    fun delete(id: Long) {
        database.transaction(exclusive = false) {
            val leader = resolveId(id) ?: return@transaction
            deleteMatching(
                "(id=? OR display_group_id=?)",
                arrayOf(leader.toString(), leader.toString())
            )
        }
    }

    fun deleteAll() {
        deleteMatching(null, null)
    }

    private data class ContentFamily(val where: String, val args: Array<String>)

    private fun contentFamily(id: Long): ContentFamily? = database.rawQuery(
        "SELECT source_package,source_kind,original_hash,original FROM alerts WHERE id=?",
        arrayOf(id.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else if (cursor.getString(1) == CaptureSource.PEER.name) {
            ContentFamily("id=?", arrayOf(id.toString()))
        } else {
            ContentFamily(
                "source_package=? AND source_kind!='PEER' AND original_hash=? AND original=?",
                arrayOf(cursor.getString(0), cursor.getString(2), cursor.getString(3))
            )
        }
    }

    /** Content grouping is a history choice, not a claim that postings are the same broadcast. */
    fun deleteContent(id: Long, notificationCandidates: Set<Long> = emptySet()): Set<Long> {
        require(notificationCandidates.size <= 40 && notificationCandidates.all { it > 0 })
        return database.transaction(exclusive = false) {
            val family = contentFamily(id) ?: return@transaction emptySet()
            val affected = if (notificationCandidates.isEmpty()) {
                emptySet()
            } else {
                database.rawQuery(
                    "SELECT id FROM alerts WHERE (${family.where}) AND id IN (" +
                        notificationCandidates.joinToString(",") { "?" } + ")",
                    family.args + notificationCandidates.map(Long::toString)
                ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            }
            deleteMatching(family.where, family.args)
            affected
        }
    }

    private fun deleteMatching(where: String?, args: Array<String>?) {
        val db = database
        db.transaction(exclusive = false) {
            val matching = where?.let { " AND $it" }.orEmpty()
            val deletedAt = System.currentTimeMillis()
            // Keep no deleted title/body. Bound source fingerprints by age and count.
            db.rawQuery(
                "SELECT source_package, source_identity, original_hash FROM alerts " +
                    "WHERE source_identity IS NOT NULL" + matching +
                    " ORDER BY id DESC LIMIT 10000",
                args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val values = ContentValues().apply {
                        put(
                            "fingerprint",
                            sourceFingerprint(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.getString(2)
                            )
                        )
                        put("deleted_ms", deletedAt)
                    }
                    db.insertWithOnConflict(
                        "ignored_sources",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
            }
            db.delete("alerts", where, args)
            pruneIgnored(db)
        }
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
                number("translation_revision"),
                TranslationEngineKind.entries.single { kind ->
                    kind.key ==
                        text("translation_engine")
                },
                number("same_language") != 0L,
                text("template_id")?.let { id ->
                    StandardTranslationAttribution(id, text("catalog_revision")!!)
                }
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
                broadcast = metadata,
                context = ReceptionContext.decode(text("reception_context"))
            ),
            translated,
            observationCount = getColumnIndex("observation_count").takeIf { it >= 0 }
                ?.let { getLong(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 1
        )
    }

    private class HistoryDatabase(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 7) {
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
                    reception_context TEXT, display_group_id INTEGER,
                    broadcast_format INTEGER, broadcast_serial INTEGER, broadcast_category INTEGER,
                    broadcast_priority INTEGER, cmas_class INTEGER, etws_type INTEGER,
                    UNIQUE(source_package, source_identity, original_hash)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX alert_original_hash ON alerts(original_hash)")
            createTranslations(db)
            createIgnoredSources(db)
            createQueueIndex(db)
            createGroupIndexes(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS alert_content_history " +
                    "ON alerts(source_package,original_hash,id DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS alert_source_kind ON alerts(source_kind,id DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS translation_catalog_revision " +
                    "ON translations(catalog_revision) WHERE template_id IS NOT NULL"
            )
            // Expire only catalog outputs. An explicit retry avoids historical notifications or provider work.
            db.execSQL(
                "UPDATE translations SET phase='STALE', revision=revision+1, text=NULL, " +
                    "source_language=NULL, search_text=NULL, same_language=0, " +
                    "template_id=NULL, catalog_revision=NULL WHERE template_id IS NOT NULL AND " +
                    "(catalog_revision IS NULL OR catalog_revision!=?)",
                arrayOf(StandardTranslationCatalog.REVISION)
            )
            pruneIgnored(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion !in 1..6 || newVersion != 7) {
                throw SQLiteException("A preserving history migration is required")
            }
            if (oldVersion <= 3) {
                if (oldVersion == 1) createIgnoredSources(db)
                db.execSQL("ALTER TABLE translations RENAME TO previous_translations")
                createTranslations(db)
                db.execSQL(
                    "INSERT INTO translations " +
                        "(alert_id,target,engine,revision,phase,text," +
                        "source_language,search_text) " +
                        "SELECT alert_id,target,'mlkit',revision,phase,text," +
                        "source_language,search_text " +
                        "FROM previous_translations"
                )
                db.execSQL("DROP TABLE previous_translations")
                createQueueIndex(db)
            }
            if (oldVersion < 5) db.execSQL("ALTER TABLE alerts ADD COLUMN reception_context TEXT")
            if (oldVersion > 3) {
                db.execSQL("ALTER TABLE translations ADD COLUMN template_id TEXT")
                db.execSQL("ALTER TABLE translations ADD COLUMN catalog_revision TEXT")
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE alerts ADD COLUMN display_group_id INTEGER")
                createGroupIndexes(db)
                AlertObservationGroups.migrate(db)
            }
        }
    }

    private companion object {
        const val IGNORE_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val CONTENT_FAMILY = "(a.source_kind='PEER' AND n.id=a.id) OR " +
            "(a.source_kind!='PEER' AND n.source_kind!='PEER' AND " +
            "n.source_package=a.source_package AND n.original_hash=a.original_hash AND n.original=a.original)"
        const val ALERT_COLUMNS = "t.phase AS translation_phase, " +
            "t.text AS translation_text, t.source_language AS source_language, " +
            "t.revision AS translation_revision, t.engine AS translation_engine, " +
            "t.same_language AS same_language, t.template_id AS template_id, " +
            "t.catalog_revision AS catalog_revision FROM alerts a LEFT JOIN translations t " +
            "ON t.alert_id=a.id AND t.target=? AND t.engine=?"

        fun selectAlert(countObservations: Boolean): String = "SELECT a.*, " +
            (
                if (countObservations) {
                    "(SELECT COUNT(*) FROM alerts n WHERE $CONTENT_FAMILY) AS observation_count, "
                } else {
                    ""
                }
                ) + ALERT_COLUMNS

        fun originalHash(original: String): String = sha256Hex(original)

        fun sourceFingerprint(source: String, identity: String, bodyHash: String): String =
            originalHash(source + '\u0000' + identity + '\u0000' + bodyHash)

        fun createGroupIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX alert_display_group ON alerts(display_group_id,id DESC)")
            db.execSQL(
                "CREATE INDEX alert_observation_window " +
                    "ON alerts(source_package,original_hash,captured_ms)"
            )
        }

        fun createIgnoredSources(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ignored_sources (fingerprint TEXT PRIMARY KEY NOT NULL, " +
                    "deleted_ms INTEGER NOT NULL)"
            )
        }

        fun createQueueIndex(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX translation_queue ON translations(engine,target,phase,alert_id DESC)"
            )
        }

        fun createTranslations(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE translations (
                    alert_id INTEGER NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
                    target TEXT NOT NULL, engine TEXT NOT NULL DEFAULT 'mlkit',
                    revision INTEGER NOT NULL, phase TEXT NOT NULL,
                    text TEXT, source_language TEXT, search_text TEXT,
                    same_language INTEGER NOT NULL DEFAULT 0,
                    template_id TEXT, catalog_revision TEXT,
                    PRIMARY KEY(alert_id, target, engine)
                )
                """.trimIndent()
            )
        }

        fun pruneIgnored(db: SQLiteDatabase) {
            db.delete(
                "ignored_sources",
                "deleted_ms<?",
                arrayOf(
                    (
                        System.currentTimeMillis() -
                            IGNORE_LIFETIME_MILLIS
                        ).toString()
                )
            )
            db.execSQL(
                "DELETE FROM ignored_sources WHERE fingerprint IN " +
                    "(SELECT fingerprint FROM ignored_sources ORDER BY deleted_ms DESC " +
                    "LIMIT -1 OFFSET 10000)"
            )
        }
    }
}
