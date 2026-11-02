package com.monostr.app.ui.common

/**
 * [map] that hands back this very list when [f] returned every element unchanged (`===`). The note lists are
 * remapped on every like, count, bookmark and tip change; a new list (and new note objects) made every shown
 * card recompose although nothing it shows had changed, and a state equal to the old one is not emitted at all.
 */
inline fun <T> List<T>.mapKeep(f: (T) -> T): List<T> {
    var out: ArrayList<T>? = null
    for (i in indices) {
        val old = this[i]
        val new = f(old)
        if (out == null && new !== old) {
            out = ArrayList(size)
            for (j in 0 until i) out.add(this[j])
        }
        out?.add(new)
    }
    return out ?: this
}

/** [mapValues] that hands back this very map when [f] returned every value unchanged (see [mapKeep]). */
inline fun <K, V> Map<K, V>.mapValuesKeep(f: (V) -> V): Map<K, V> {
    var out: LinkedHashMap<K, V>? = null
    for ((k, old) in this) {
        val new = f(old)
        if (out == null && new !== old) {
            out = LinkedHashMap(size)
            for ((k2, v2) in this) { if (k2 == k) break; out[k2] = v2 }
        }
        out?.put(k, new)
    }
    return out ?: this
}
