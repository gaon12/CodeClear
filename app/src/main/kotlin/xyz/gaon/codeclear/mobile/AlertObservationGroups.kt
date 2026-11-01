package xyz.gaon.codeclear.mobile

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Groups nearby cross-route presentations without discarding any source observation. */
internal object AlertObservationGroups {
    private const val WINDOW_MILLIS = 5_000L
    private const val CANDIDATE_LIMIT = 8

    private data class Observation(
        val id: Long,
        val group: Long?,
        val source: String,
        val kind: String,
        val identity: String?,
        val body: String,
        val hash: String,
        val captured: Long,
        val received: Long?,
        val format: Int?,
        val serial: Int?,
        val category: Int?,
        val priority: Int?,
        val cmas: Int?,
        val etws: Int?
    )

    fun assign(db: SQLiteDatabase, id: Long, capture: CapturedAlert, hash: String): Long = assign(
        db,
        Observation(
            id, null, capture.sourcePackage, capture.source.name, capture.sourceIdentity,
            capture.original, hash, capture.capturedAtMillis, capture.receivedAtMillis,
            capture.broadcast?.format, capture.broadcast?.serial, capture.broadcast?.category,
            capture.broadcast?.priority, capture.broadcast?.cmasClass, capture.broadcast?.etwsType
        )
    )

    private fun assign(db: SQLiteDatabase, current: Observation): Long {
        val id = current.id
        current.group?.let { return it }
        if (current.kind == "PEER" || current.identity == null) return id
        val upper = if (current.captured > Long.MAX_VALUE - WINDOW_MILLIS) {
            Long.MAX_VALUE
        } else {
            current.captured + WINDOW_MILLIS
        }
        val candidates = read(
            db,
            "source_package=? AND original_hash=? AND captured_ms BETWEEN ? AND ? AND id<?",
            arrayOf(
                current.source,
                current.hash,
                maxOf(1L, current.captured - WINDOW_MILLIS).toString(),
                upper.toString(),
                id.toString()
            ),
            CANDIDATE_LIMIT
        )
        // A saturated or ambiguous set remains separate rather than guessing a match.
        if (candidates.size == CANDIDATE_LIMIT) return id
        val priorGroups = candidates.map { it.group ?: it.id }.distinct().associateWith { leader ->
            read(db, "id=? OR display_group_id=?", arrayOf(leader.toString(), leader.toString()), 4)
        }
        val eligible = priorGroups.filterValues { canJoin(it, listOf(current)) }
        val leader = eligible.keys.singleOrNull() ?: return id
        val members = priorGroups.getValue(leader)
        // An earlier unmatched observation must not become a false unique match for a later event.
        if (priorGroups.any { (other, observations) ->
                other != leader && observations.any { it.kind == current.kind } &&
                    canJoin(members, observations)
            }
        ) {
            return id
        }
        db.update(
            "alerts",
            ContentValues().apply { put("display_group_id", leader) },
            "id=?",
            arrayOf(id.toString())
        )
        copyTranslations(db, id, leader)
        db.execSQL(
            "UPDATE translations SET revision=revision+1,phase='UNAVAILABLE' " +
                "WHERE alert_id=? AND phase IN ('QUEUED','WORKING')",
            arrayOf(id)
        )
        return leader
    }

    fun migrate(db: SQLiteDatabase) {
        var after = 0L
        while (true) {
            val ids = db.rawQuery(
                "SELECT id FROM alerts WHERE id>? ORDER BY id LIMIT 100",
                arrayOf(after.toString())
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            if (ids.isEmpty()) return
            ids.forEach { assign(db, read(db, "id=?", arrayOf(it.toString()), 1).single()) }
            after = ids.last()
        }
    }

    private fun canJoin(first: List<Observation>, second: List<Observation>): Boolean {
        val all = first + second
        if (first.isEmpty() || second.isEmpty() || all.size !in 2..3 ||
            all.any { it.kind == "PEER" || it.identity == null } ||
            all.map { it.kind }.distinct().size != all.size ||
            all.map { it.source }.distinct().size != 1 ||
            all.map { it.body }.distinct().size != 1 ||
            all.maxOf { it.captured } - all.minOf { it.captured } > WINDOW_MILLIS
        ) {
            return false
        }
        return listOf(
            all.mapNotNull { it.received },
            all.mapNotNull { it.format },
            all.mapNotNull { it.serial },
            all.mapNotNull { it.category },
            all.mapNotNull { it.priority },
            all.mapNotNull { it.cmas },
            all.mapNotNull { it.etws }
        ).all { it.distinct().size <= 1 }
    }

    private fun read(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
        limit: Int
    ): List<Observation> = db.rawQuery(
        "SELECT id,display_group_id,source_package,source_kind,source_identity," +
            "original,original_hash,captured_ms,received_ms,broadcast_format,broadcast_serial," +
            "broadcast_category,broadcast_priority,cmas_class,etws_type FROM alerts " +
            "WHERE $where ORDER BY id DESC LIMIT $limit",
        args
    ).use { c ->
        fun optional(index: Int): Long? = if (c.isNull(index)) null else c.getLong(index)
        buildList {
            while (c.moveToNext()) {
                add(
                    Observation(
                        id = c.getLong(0),
                        group = optional(1),
                        source = c.getString(2),
                        kind = c.getString(3),
                        identity = c.getString(4),
                        body = c.getString(5),
                        hash = c.getString(6),
                        captured = c.getLong(7),
                        received = optional(8),
                        format = optional(9)?.toInt(),
                        serial = optional(10)?.toInt(),
                        category = optional(11)?.toInt(),
                        priority = optional(12)?.toInt(),
                        cmas = optional(13)?.toInt(),
                        etws = optional(14)?.toInt()
                    )
                )
            }
        }
    }

    private fun copyTranslations(db: SQLiteDatabase, member: Long, leader: Long) {
        // Retain all member results and prefer a ready result over a missing/unfinished leader.
        db.execSQL(
            "INSERT OR REPLACE INTO translations " +
                "(alert_id,target,engine,revision,phase,text," +
                "source_language,search_text,same_language) " +
                "SELECT ?,c.target,c.engine,c.revision,c.phase,c.text," +
                "c.source_language,c.search_text," +
                "c.same_language FROM translations c WHERE c.alert_id=? AND (NOT EXISTS " +
                "(SELECT 1 FROM translations p WHERE p.alert_id=? " +
                "AND p.target=c.target AND p.engine=c.engine) " +
                "OR (c.phase='READY' AND EXISTS (SELECT 1 FROM translations p WHERE p.alert_id=? " +
                "AND p.target=c.target AND p.engine=c.engine AND p.phase!='READY')))",
            arrayOf(leader, member, leader, leader)
        )
    }
}
