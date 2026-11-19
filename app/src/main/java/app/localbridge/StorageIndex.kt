package app.localbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.documentfile.provider.DocumentFile
import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Persistent, lightweight index of the selected shared-storage tree. */
class StorageIndex(
    context: Context,
    private val root: DocumentFile,
    private val fileRoot: File? = null,
    rootKey: String,
    private val onState: (Boolean, Long, String?) -> Unit = { _, _, _ -> }
) {
    private val db = IndexDatabase(context.applicationContext, "lobby-index-${rootKey.hashCode()}.db")
    private val worker = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val observers = ConcurrentHashMap<String, FileObserver>()
    @Volatile private var closed = false

    fun start() {
        worker.execute { fullScan() }
        scheduler.scheduleWithFixedDelay({
            if (!closed) worker.execute { fullScan() }
        }, 15, 15, TimeUnit.MINUTES)
    }

    fun count(): Long = db.count()

    fun children(relativeDirectory: String): List<IndexedEntry> = db.children(relativeDirectory)

    fun refresh(relativePath: String = "") {
        if (closed) return
        worker.execute {
            val parent = relativePath.substringBeforeLast('/', "")
            val directory = findDirectory(parent) ?: root
            scanDirectory(directory, parent)
            onState(false, db.count(), null)
        }
    }

    private fun fullScan() {
        if (closed) return
        onState(true, db.count(), null)
        try {
            scanDirectory(root, "")
            fileRoot?.let { base ->
                observers.entries.toList().forEach { entry ->
                    if (!File(base, entry.key).isDirectory) {
                        entry.value.stopWatching()
                        observers.remove(entry.key)
                    }
                }
                registerObservers(base, "")
            }
            onState(false, db.count(), System.currentTimeMillis().toString())
        } catch (_: Exception) {
            onState(false, db.count(), null)
        }
    }

    private fun scanDirectory(directory: DocumentFile, relative: String) {
        if (closed) return
        val found = try {
            if (!directory.exists() || !directory.isDirectory) return
            directory.listFiles()
        } catch (_: Exception) {
            return
        }
        val seen = HashSet<String>()
        found.forEach { child ->
            val name = child.name ?: return@forEach
            if (name == "." || name == "..") return@forEach
            val path = if (relative.isEmpty()) name else "$relative/$name"
            seen.add(path)
            db.put(IndexedEntry(path, relative, name, child.isDirectory, child.length(), child.lastModified()))
            if (child.isDirectory) {
                scanDirectory(child, path)
                fileRoot?.let { registerObserver(File(it, path), path) }
            }
        }
        db.removeMissingChildren(relative, seen)
    }

    private fun registerObservers(directory: File, relative: String) {
        if (!directory.isDirectory || observers.containsKey(relative)) return
        try {
            val observer = object : FileObserver(
                directory.absolutePath,
                CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or ATTRIB or DELETE_SELF or MOVE_SELF
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (closed) return
                    worker.execute {
                        val doc = if (relative.isEmpty()) root else findDirectory(relative)
                        if (doc != null) {
                            scanDirectory(doc, relative)
                            onState(false, db.count(), null)
                        }
                    }
                }
            }
            observers[relative] = observer
            observer.startWatching()
        } catch (_: Exception) { }
    }

    private fun registerObserver(directory: File, relative: String) = registerObservers(directory, relative)

    private fun findDirectory(relative: String): DocumentFile? {
        if (relative.isBlank()) return root
        var current = root
        relative.split('/').forEach { segment ->
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    fun close() {
        closed = true
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        scheduler.shutdownNow()
        worker.shutdownNow()
        db.close()
    }
}

data class IndexedEntry(
    val path: String,
    val parent: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long
)

private class IndexDatabase(context: Context, databaseName: String) : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(path TEXT PRIMARY KEY, parent TEXT NOT NULL, name TEXT NOT NULL, directory INTEGER NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX entries_parent ON entries(parent, directory, name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    @Synchronized fun put(entry: IndexedEntry) {
        val values = ContentValues().apply {
            put("path", entry.path); put("parent", entry.parent); put("name", entry.name)
            put("directory", if (entry.directory) 1 else 0); put("size", entry.size); put("modified", entry.modified)
        }
        writableDatabase.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun children(parent: String): List<IndexedEntry> {
        val result = ArrayList<IndexedEntry>()
        readableDatabase.query("entries", null, "parent=?", arrayOf(parent), null, null, "directory DESC, name COLLATE NOCASE ASC").use { cursor ->
            while (cursor.moveToNext()) result.add(
                IndexedEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0, cursor.getLong(4), cursor.getLong(5))
            )
        }
        return result
    }

    @Synchronized fun removeMissingChildren(parent: String, seen: Set<String>) {
        val existing = readableDatabase.rawQuery("SELECT path FROM entries WHERE parent=?", arrayOf(parent)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val writable = writableDatabase
        existing.filterNot { it in seen }.forEach { stale ->
            writable.delete("entries", "path=? OR substr(path,1,length(?)+1)=? || '/'", arrayOf(stale, stale, stale))
        }
    }

    @Synchronized fun clear() { writableDatabase.delete("entries", null, null) }
    @Synchronized fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
