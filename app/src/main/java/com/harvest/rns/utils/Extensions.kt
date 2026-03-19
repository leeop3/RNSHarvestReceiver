package com.harvest.rns.utils

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

// ─── View ─────────────────────────────────────────────────────────────────────

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }

fun View.showIf(condition: Boolean) {
    visibility = if (condition) View.VISIBLE else View.GONE
}

// ─── Context ──────────────────────────────────────────────────────────────────

fun Context.toast(message: String, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

// ─── LiveData ─────────────────────────────────────────────────────────────────

fun <T> LiveData<T>.observeOnce(owner: LifecycleOwner, observer: Observer<T>) {
    observe(owner, object : Observer<T> {
        override fun onChanged(value: T) {
            observer.onChanged(value)
            removeObserver(this)
        }
    })
}

// ─── Numbers ──────────────────────────────────────────────────────────────────

fun Int.formatThousands(): String {
    return when {
        this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
        this >= 1_000     -> "%.1fK".format(this / 1_000.0)
        else              -> this.toString()
    }
}

fun Double.toCoordinateString(): String = "%.5f".format(this)

// ─── String ───────────────────────────────────────────────────────────────────

fun String.truncate(maxLen: Int, suffix: String = "…"): String {
    return if (length <= maxLen) this else "${take(maxLen - suffix.length)}$suffix"
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
