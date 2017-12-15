package org.zanata.proxyhook.server

import java.util.ArrayList
import java.util.HashMap
import java.util.Objects
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Future.succeededFuture
import io.vertx.core.Handler
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.LocalMap

// From https://github.com/eclipse/vert.x/issues/2137#issuecomment-330824746
// Thanks to https://github.com/mr-bre
class LocalAsyncMap<K, V>(private val map: LocalMap<K, V>) : AsyncMap<K, V> {

    override fun get(k: K, resultHandler: Handler<AsyncResult<V>>) {
        resultHandler.handle(succeededFuture(map[k]))
    }

    override fun put(k: K, v: V, completionHandler: Handler<AsyncResult<Void>>) {
        map.put(k, v)
        completionHandler.handle(succeededFuture())
    }

    override fun put(k: K, v: V, ttl: Long, completionHandler: Handler<AsyncResult<Void>>) {
        put(k, v, completionHandler)
    }

    override fun putIfAbsent(k: K, v: V, completionHandler: Handler<AsyncResult<V>>) {
        completionHandler.handle(succeededFuture(map.putIfAbsent(k, v)))
    }

    override fun putIfAbsent(k: K, v: V, ttl: Long, completionHandler: Handler<AsyncResult<V>>) {
        putIfAbsent(k, v, completionHandler)
    }

    override fun remove(k: K, resultHandler: Handler<AsyncResult<V>>) {
        resultHandler.handle(succeededFuture(map.remove(k)))
    }

    override fun removeIfPresent(k: K, v: V, resultHandler: Handler<AsyncResult<Boolean>>) {
        resultHandler.handle(succeededFuture(map.removeIfPresent(k, v)))
    }

    override fun replace(k: K, v: V, resultHandler: Handler<AsyncResult<V>>) {
        resultHandler.handle(succeededFuture(map.replace(k, v)))
    }

    override fun replaceIfPresent(k: K, oldValue: V, newValue: V, resultHandler: Handler<AsyncResult<Boolean>>) {
        resultHandler.handle(succeededFuture(map.replaceIfPresent(k, oldValue, newValue)))
    }

    override fun clear(resultHandler: Handler<AsyncResult<Void>>) {
        map.clear()
        resultHandler.handle(succeededFuture())
    }

    override fun size(resultHandler: Handler<AsyncResult<Int>>) {
        resultHandler.handle(succeededFuture(map.size))
    }

    override fun keys(resultHandler: Handler<AsyncResult<Set<K>>>) {
        resultHandler.handle(succeededFuture(map.keys))
    }

    override fun values(resultHandler: Handler<AsyncResult<List<V>>>) {
        val result = ArrayList(map.values)
        resultHandler.handle(succeededFuture(result))
    }

    override fun entries(resultHandler: Handler<AsyncResult<Map<K, V>>>) {
        val result = entriesToMap<K, V>(map.entries)
        resultHandler.handle(succeededFuture(result))
    }

    private fun <K, V> entriesToMap(entries: Set<MutableMap.MutableEntry<K, V>>): Map<K, V> {
        val map = HashMap<K, V>(entries.size * 2)
        for (entry in entries) {
            map.put(entry.key, entry.value)
        }
        return map
    }
}
