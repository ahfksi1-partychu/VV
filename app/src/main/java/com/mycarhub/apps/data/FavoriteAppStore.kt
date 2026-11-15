package com.mycarhub.apps.data

import android.content.Context
import com.mycarhub.apps.model.FavoriteApp
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 등록 앱 목록 저장소. 목록 순서가 곧 화면 표시 순서다. */
interface FavoriteAppStore {
    fun load(): List<FavoriteApp>
    fun save(apps: List<FavoriteApp>)
}

class SharedPrefsFavoriteAppStore(context: Context) : FavoriteAppStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<FavoriteApp> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toFavoriteApp() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    override fun save(apps: List<FavoriteApp>) {
        val array = JSONArray()
        apps.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putInt(KEY_VERSION, SCHEMA_VERSION)
            .putString(KEY_APPS, array.toString())
            .apply()
    }

    private fun FavoriteApp.toJson() = JSONObject()
        .put("packageName", packageName)
        .put("activityName", activityName)
        .put("label", label)
        .put("alias", alias ?: JSONObject.NULL)

    private fun JSONObject.toFavoriteApp(): FavoriteApp? {
        val packageName = optString("packageName").takeIf { it.isNotEmpty() } ?: return null
        val activityName = optString("activityName").takeIf { it.isNotEmpty() } ?: return null
        return FavoriteApp(
            packageName = packageName,
            activityName = activityName,
            label = optString("label", packageName),
            alias = if (isNull("alias")) null else optString("alias").takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val PREFS_NAME = "favorite_apps"
        const val KEY_APPS = "apps"
        const val KEY_VERSION = "schema_version"
        const val SCHEMA_VERSION = 1
    }
}
