package com.flow.app.data

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Persistent plan definitions only. Runtime state is intentionally absent. */
class PlanStore(private val file: File) {
    private val lock = Any()
    fun loadAll(): List<Routine> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("schemaVersion", -1) != 1) error("unsupported schema")
            val array = root.optJSONArray("plans") ?: JSONArray()
            val plans = (0 until array.length()).map { decode(array.getJSONObject(it)) }
            require(plans.map { it.id }.distinct().size == plans.size)
            plans
        } catch (e: Exception) { throw IllegalStateException("Unable to read plans", e) }
    }

    fun save(plan: Routine): Boolean = synchronized(lock) {
        if (!plan.isValid) return false
        try {
            val plans = if (file.exists()) loadAll().toMutableList() else mutableListOf()
            val index = plans.indexOfFirst { it.id == plan.id }
            if (index >= 0) plans[index] = plan else plans.add(plan)
            write(plans)
            true
        } catch (_: Exception) { false }
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        try {
            if (!file.exists()) return true
            write(loadAll().filterNot { it.id == id })
            true
        } catch (_: Exception) { false }
    }

    private fun write(plans: List<Routine>) {
        val root = JSONObject().put("schemaVersion", 1)
            .put("plans", JSONArray().also { a -> plans.forEach { a.put(encode(it)) } })
        val parent = file.parentFile ?: error("no parent")
        if (!parent.exists() && !parent.mkdirs()) error("mkdir")
        val temp = File(parent, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(root.toString().toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) { if (!temp.renameTo(file)) throw e }
    }

    private fun encode(plan: Routine) = JSONObject()
        .put("id", plan.id).put("name", plan.name).put("version", 1)
        .put("createdAt", plan.createdAt).put("updatedAt", plan.updatedAt)
        .put("steps", JSONArray().also { a -> plan.steps.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name)
                .put("type", s.type.name)
                .put("target", s.target ?: JSONObject.NULL)
                .put("durationMs", s.durationMs ?: JSONObject.NULL))
        } })

    private fun decode(json: JSONObject): Routine {
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).map { i ->
            val s = stepsJson.getJSONObject(i)
            val type = StepType.valueOf(s.getString("type"))
            val target = if (s.isNull("target")) null else s.getInt("target")
            val duration = if (s.isNull("durationMs")) null else s.getLong("durationMs")
            if (type == StepType.COUNT) {
                require(target != null && target > 0 && duration == null)
            } else {
                require(duration != null && duration > 0 && target == null)
            }
            RoutineStep(
                id = s.getString("id"), name = s.getString("name"), type = type,
                target = target,
                countMode = if (type == StepType.COUNT) CountMode.COMPLETE else null,
                durationMs = duration,
                category = if (type == StepType.TIMER) TimerCategory.NORMAL else null,
                repeatCount = 1
            )
        }
        val plan = Routine(
            id = json.getString("id"), name = json.getString("name"),
            version = 1, repeatCount = 1, steps = steps,
            createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt")
        )
        require(plan.isValid)
        return plan
    }
}
