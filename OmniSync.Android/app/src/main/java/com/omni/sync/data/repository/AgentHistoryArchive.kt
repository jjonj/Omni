package com.omni.sync.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Opt-in, private on-device observed-history archive.
 *
 * The Hub/provider owns transcript completeness and revisions. This database
 * stores only entries that were actually observed, never marks DOM-derived
 * excerpts as full history, and never launches provider history scans itself.
 */
class AgentHistoryArchive(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "agent-history-v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE history (
                session TEXT NOT NULL,
                item_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                kind TEXT NOT NULL,
                body TEXT NOT NULL,
                partial INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(session, item_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX history_by_session ON history(session, updated_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Do not delete observed history during a future schema migration.
        throw IllegalStateException("No automatic history migration available")
    }
    fun record(session: String, page: List<AgentHistoryUi>) {
        require(session.length <= 300)
        val db=writableDatabase
        db.beginTransaction()
        try {
            val stamp=System.currentTimeMillis()
            for (item in page) {
                if(item.id.length !in 1..256 || item.text.length > 1_000_000) continue
                val values=ContentValues().apply {
                    put("session",session)
                    put("item_id",item.id)
                    put("revision",item.revision)
                    put("kind",item.kind)
                    put("body",item.text)
                    put("partial",if(item.partial) 1 else 0)
                    put("updated_at",stamp)
                }
                // Preserve newer streamed revisions already in the archive.
                db.execSQL("""
                    INSERT INTO history (session,item_id,revision,kind,body,partial,updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(session,item_id) DO UPDATE SET
                      revision=excluded.revision, kind=excluded.kind,
                      body=excluded.body, partial=excluded.partial,
                      updated_at=excluded.updated_at
                    WHERE excluded.revision>=history.revision
                """.trimIndent(),arrayOf(
                    session,item.id,item.revision,item.kind,item.text,
                    if(item.partial)1 else 0,stamp
                ))
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun read(session:String,limit:Int=500,offset:Int=0):List<AgentHistoryUi>{
        require(session.length<=300)
        val items=mutableListOf<AgentHistoryUi>()
        readableDatabase.rawQuery(
            "SELECT item_id,revision,kind,body,partial FROM history WHERE session=? ORDER BY updated_at DESC, item_id DESC LIMIT ? OFFSET ?",
            arrayOf(session,limit.coerceIn(1,500).toString(),offset.coerceAtLeast(0).toString())
        ).use {cursor->
            while(cursor.moveToNext()){
                items.add(AgentHistoryUi(cursor.getString(0),cursor.getString(2),cursor.getString(3),
                    cursor.getInt(4)!=0,cursor.getLong(1)))
            }
        }
        return items.reversed()
    }
    fun search(text:String,limit:Int=100):List<Pair<String,AgentHistoryUi>>{
        if(text.isBlank())return emptyList()
        val matches=mutableListOf<Pair<String,AgentHistoryUi>>()
        val needle="%"+text.take(100).replace("%","\\%").replace("_","\\_")+"%"
        readableDatabase.rawQuery(
            "SELECT session,item_id,revision,kind,body,partial FROM history WHERE body LIKE ? ESCAPE '\\' ORDER BY updated_at DESC LIMIT ?",
            arrayOf(needle,limit.coerceIn(1,100).toString())
        ).use {cursor->
            while(cursor.moveToNext()){
                matches.add(cursor.getString(0) to AgentHistoryUi(
                    cursor.getString(1),cursor.getString(3),cursor.getString(4),
                    cursor.getInt(5)!=0,cursor.getLong(2)))
            }
        }
        return matches
    }
}
