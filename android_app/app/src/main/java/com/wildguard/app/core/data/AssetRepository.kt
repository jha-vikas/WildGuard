package com.wildguard.app.core.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class AssetRepository(private val context: Context) {

    private val gson = Gson()

    fun readJsonString(filename: String): String {
        return context.assets.open(filename).bufferedReader().use { it.readText() }
    }

    inline fun <reified T> readJson(filename: String): T {
        val reader = InputStreamReader(context.assets.open(filename))
        return reader.use { gson.fromJson(it, object : TypeToken<T>() {}.type) }
    }

    inline fun <reified T> readJsonList(filename: String): List<T> {
        val reader = InputStreamReader(context.assets.open(filename))
        return reader.use { gson.fromJson(it, object : TypeToken<List<T>>() {}.type) }
    }
}
