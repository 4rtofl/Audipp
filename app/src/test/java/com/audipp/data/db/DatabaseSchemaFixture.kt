package com.audipp.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject

internal fun Context.createLibraryDatabaseFromSchema(
    name: String, version: Int, populate: (SQLiteDatabase) -> Unit,
) {
    val schema = JSONObject(File("schemas/com.audipp.data.db.LibraryDatabase/$version.json").readText())
        .getJSONObject("database")
    openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val table = entities.getJSONObject(index)
            val tableName = table.getString("tableName")
            db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = table.optJSONArray("indices") ?: continue
            for (i in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
        populate(db)
        db.version = version
    }
}
