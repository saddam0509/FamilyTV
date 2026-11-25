package local.familytv

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Looks up and caches TMDB genre/rating metadata for movies.
 *
 * Results are kept in memory for the session and on disk (SharedPreferences) so
 * repeat browsing does not re-hit the API. A miss is cached as empty metadata so
 * it is not retried every time. Lookups run on a small background pool; callbacks
 * are delivered on the main thread.
 */
class Enrichment(private val store: SharedPreferences) {
    private val pool = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, MovieMeta>(2048)
    private val inflight = Collections.synchronizedSet(mutableSetOf<String>())
    private val maxEntries = 1500
    private var order: ArrayDeque<String>? = null

    /** Cached metadata if available (memory, then disk); never hits the network. */
    fun cached(movie: Movie): MovieMeta? = cachedByKey(movie.id)

    private fun cachedByKey(key: String): MovieMeta? =
        cache.get(key) ?: store.getString("meta:$key", null)?.let { stored ->
            decodeMeta(stored).also { cache.put(key, it) }
        }

    /** Delivers cached movie genre/rating immediately, else looks it up in the background. */
    fun enrich(movie: Movie, onDone: (MovieMeta) -> Unit) {
        cachedByKey(movie.id)?.let { onDone(it); return }
        if (!inflight.add(movie.id)) return
        pool.execute {
            val meta = runCatching { Tmdb.lookup(movie) }.getOrDefault(MovieMeta(emptyList(), null))
            cache.put(movie.id, meta)
            runCatching { persist(movie.id, meta) }
            inflight.remove(movie.id)
            main.post { onDone(meta) }
        }
    }

    /** Looks up TV show metadata (poster+genres+rating), cached by title+year; shares the bounded cache. */
    fun showMeta(title: String, year: String?, onDone: (MovieMeta) -> Unit) {
        val key = "tv:" + titleKey(title) + ":" + year.orEmpty()
        cachedByKey(key)?.let { onDone(it); return }
        if (!inflight.add(key)) return
        pool.execute {
            val meta = runCatching { Tmdb.tvMeta(title, year) }.getOrDefault(MovieMeta(emptyList(), null))
            cache.put(key, meta)
            runCatching { persist(key, meta) }
            inflight.remove(key)
            main.post { onDone(meta) }
        }
    }

    /** Cached TV show metadata (never hits the network). */
    fun cachedShow(title: String, year: String?): MovieMeta? =
        cachedByKey("tv:" + titleKey(title) + ":" + year.orEmpty())

    // Persist with a bounded, insertion-ordered key list so the on-disk cache
    // can never grow past maxEntries (evicts the oldest). IDs never contain a
    // newline, so the order list stores them newline-separated (cheap).
    @Synchronized
    private fun persist(id: String, meta: MovieMeta) {
        val keys = order ?: loadOrder().also { order = it }
        val (kept, removed) = capOrder(keys, id, maxEntries)
        val editor = store.edit().putString("meta:$id", encodeMeta(meta))
        removed.forEach { editor.remove("meta:$it") }
        editor.putString("meta-order", kept.joinToString("\n")).apply()
        order = ArrayDeque(kept)
    }

    private fun loadOrder(): ArrayDeque<String> {
        val saved = store.getString("meta-order", "")?.split("\n")?.filter(String::isNotEmpty).orEmpty()
        if (saved.isNotEmpty()) return ArrayDeque(saved)
        // Migration: adopt any meta: entries written before ordering existed so
        // the cap governs them too (otherwise they'd never be evicted).
        return ArrayDeque(store.all.keys.filter { it.startsWith("meta:") }.map { it.removePrefix("meta:") })
    }

    fun close() {
        pool.shutdownNow()
    }
}

/** Caps an insertion-ordered key list (most-recent-last) at [cap]; returns (kept, evicted). */
fun capOrder(order: List<String>, newKey: String, cap: Int): Pair<List<String>, List<String>> {
    val kept = order.filter { it != newKey }.toMutableList()
    kept.add(newKey)
    if (kept.size <= cap) return kept to emptyList()
    val removed = kept.subList(0, kept.size - cap).toList()
    return kept.subList(kept.size - cap, kept.size).toList() to removed
}
