package io.github.akhil838.prism.core

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executor for background work (decodes, blurs, package scans, downloads) whose threads run below
 * the UI. Threads started from the main thread inherit its foreground priority, so this work
 * competed with the UI thread and RenderThread for the TV's four small cores and cost frames.
 *
 * Nice 9 (background + 1, as Glide uses for its loaders): below the UI, but not in Android's
 * background scheduling group, which only gets ~5% of the CPU and would make shelf art crawl.
 */
fun backgroundPool(name: String, threads: Int = 1): ExecutorService {
    val n = AtomicInteger()
    return Executors.newFixedThreadPool(threads) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            r.run()
        }, "$name-${n.incrementAndGet()}")
    }
}

/**
 * The UI's background work, one job at a time, latest request per kind wins.
 *
 * Fast navigation asks for a shelf query, a backdrop render, a tint blur... for every app or card it
 * passes. Run as they come, they pile onto the TV's four small cores while the UI animates; here a
 * newer request of the same kind replaces one still waiting, so only what the user rests on is
 * computed, strictly one job after another, below the UI's priority.
 */
object UiWork {
    private val thread = backgroundPool("PrismWork")
    private val waiting = HashMap<String, () -> Unit>()

    /** Queues [task] as the latest job of [kind]; an earlier [kind] job that hasn't started is dropped. */
    fun submit(kind: String, task: () -> Unit) {
        val scheduled = synchronized(waiting) { waiting.put(kind, task) != null }
        if (scheduled) return // the runner already queued for this kind will pick up the new task
        thread.execute {
            val t = synchronized(waiting) { waiting.remove(kind) }
            t?.invoke()
        }
    }
}
