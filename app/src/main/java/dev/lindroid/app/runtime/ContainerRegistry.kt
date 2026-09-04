package dev.lindroid.app.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LxContainer(val id: String, val flavor: DistroFlavor, val name: String)

/**
 * The set of containers the user has created, plus which one the app screens
 * act on. Stored as JSON in app files. The very first load seeds the registry
 * with the historical single Debian container so existing installs keep their
 * rootfs untouched.
 */
object ContainerRegistry {
    private const val FILE_NAME = "containers.json"

    data class Snapshot(val containers: List<LxContainer>, val activeId: String)

    @Synchronized
    fun load(context: Context): Snapshot {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) {
            val snapshot = Snapshot(
                listOf(LxContainer(RuntimePaths.DEFAULT_ID, DistroFlavor.DEBIAN, "Debian 12")),
                RuntimePaths.DEFAULT_ID,
            )
            write(context, snapshot.containers, snapshot.activeId)
            return snapshot
        }
        return runCatching {
            val root = JSONObject(file.readText())
            val containers = root.optJSONArray("containers") ?: JSONArray()
                .put(JSONObject().put("id", RuntimePaths.DEFAULT_ID).put("flavor", DistroFlavor.DEBIAN.name).put("name", "Debian 12"))
            val list = buildList {
                for (index in 0 until containers.length()) {
                    val item = containers.getJSONObject(index)
                    val id = item.optString("id").ifBlank { continue }
                    val flavor = runCatching { DistroFlavor.valueOf(item.optString("flavor")) }.getOrDefault(DistroFlavor.DEBIAN)
                    add(LxContainer(id, flavor, item.optString("name").ifBlank { flavor.label }))
                }
            }
            val active = root.optString("active").takeIf { it.isNotBlank() }
            val snapshot = Snapshot(list, active ?: list.firstOrNull()?.id.orEmpty())
            if (snapshot.containers.isEmpty()) {
                // Never end up with zero entries; recreating the default keeps
                // the screens meaningful after the last container was removed.
                write(context, defaultList(), RuntimePaths.DEFAULT_ID)
                Snapshot(defaultList(), RuntimePaths.DEFAULT_ID)
            } else {
                snapshot
            }
        }.getOrElse {
            Snapshot(defaultList(), RuntimePaths.DEFAULT_ID)
        }
    }

    @Synchronized
    fun save(context: Context, containers: List<LxContainer>, activeId: String) {
        write(context, containers, activeId)
    }

    @Synchronized
    fun find(context: Context, id: String): LxContainer? =
        load(context).containers.firstOrNull { it.id == id }

    @Synchronized
    fun uniqueId(context: Context, base: String): String {
        val taken = load(context).containers.map { it.id }.toSet()
        if (base !in taken) return base
        var index = 2
        while ("$base-$index" in taken) index++
        return "$base-$index"
    }

    @Synchronized
    fun add(context: Context, container: LxContainer) {
        val snapshot = load(context)
        write(context, snapshot.containers + container, container.id)
    }

    @Synchronized
    fun setActive(context: Context, id: String) {
        val snapshot = load(context)
        if (snapshot.containers.any { it.id == id }) write(context, snapshot.containers, id)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val snapshot = load(context)
        val remaining = snapshot.containers.filter { it.id != id }
        if (remaining.size == snapshot.containers.size) return
        val active = if (snapshot.activeId == id) remaining.firstOrNull()?.id.orEmpty() else snapshot.activeId
        write(context, remaining, active)
    }

    private fun defaultList() = listOf(LxContainer(RuntimePaths.DEFAULT_ID, DistroFlavor.DEBIAN, "Debian 12"))

    private fun write(context: Context, containers: List<LxContainer>, activeId: String) {
        val array = JSONArray()
        containers.forEach { container ->
            array.put(
                JSONObject()
                    .put("id", container.id)
                    .put("flavor", container.flavor.name)
                    .put("name", container.name),
            )
        }
        val root = JSONObject().put("containers", array).put("active", activeId)
        val file = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.writeText(root.toString())
        check(temp.renameTo(file)) { "Could not store the container list" }
    }
}
