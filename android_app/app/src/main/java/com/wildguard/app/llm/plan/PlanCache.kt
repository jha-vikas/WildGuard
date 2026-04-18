package com.wildguard.app.llm.plan

import android.content.Context
import com.google.gson.Gson
import java.io.File

class PlanCache(private val context: Context) {

    private val gson = Gson()
    private val cacheFile: File
        get() = File(context.filesDir, "trip_plan_cache.json")

    fun save(plan: TripPlan) {
        cacheFile.writeText(gson.toJson(plan))
    }

    fun load(): TripPlan? = try {
        val file = cacheFile
        if (file.exists()) {
            gson.fromJson(file.readText(), TripPlan::class.java)
        } else null
    } catch (_: Exception) {
        null
    }

    fun isFresh(): Boolean {
        val plan = load() ?: return false
        return System.currentTimeMillis() < plan.validUntil
    }

    fun isStale(): Boolean {
        val plan = load() ?: return false
        return System.currentTimeMillis() >= plan.validUntil
    }

    fun clear() {
        val file = cacheFile
        if (file.exists()) file.delete()
    }
}
