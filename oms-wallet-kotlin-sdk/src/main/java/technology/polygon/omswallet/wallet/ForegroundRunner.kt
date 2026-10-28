package technology.polygon.omswallet.wallet

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs work once the app is in use. A process started in the background (a push message, a
 * scheduled job, or boot) is not user activity, so work that extends the session waits for it.
 */
internal fun interface ForegroundRunner {
    /** Returns a function that cancels the work if it has not run yet. */
    fun runInForeground(action: () -> Unit): () -> Unit
}

/** Runs right away; used where no Android context is available. */
internal val ImmediateForegroundRunner =
    ForegroundRunner { action ->
        action()
        return@ForegroundRunner {}
    }

/**
 * Runs now when the app has a started activity; otherwise once, on the main thread, when it next
 * has one. It follows the process lifecycle rather than the process importance, which is also
 * foreground while a broadcast receiver or service runs with no activity on screen.
 */
internal class AndroidForegroundRunner(
    private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
) : ForegroundRunner {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun runInForeground(action: () -> Unit): () -> Unit {
        val lifecycle = lifecycleOwner.lifecycle
        // Set once the work ran or was cancelled; the observer is only touched on the main thread.
        val settled = AtomicBoolean(false)
        var observer: LifecycleEventObserver? = null
        val start =
            Runnable {
                if (settled.get()) return@Runnable
                val state = lifecycle.currentState
                // INITIALIZED: the app disabled the process lifecycle's startup initializer, so it
                // never reports started; run now rather than never.
                if (state.isAtLeast(Lifecycle.State.STARTED) || state == Lifecycle.State.INITIALIZED) {
                    if (settled.compareAndSet(false, true)) action()
                    return@Runnable
                }
                observer =
                    object : LifecycleEventObserver {
                        override fun onStateChanged(
                            source: LifecycleOwner,
                            event: Lifecycle.Event,
                        ) {
                            if (event != Lifecycle.Event.ON_START) return
                            lifecycle.removeObserver(this)
                            if (settled.compareAndSet(false, true)) action()
                        }
                    }.also(lifecycle::addObserver)
            }
        // The lifecycle accepts observers only on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) start.run() else mainHandler.post(start)
        return {
            if (settled.compareAndSet(false, true)) {
                mainHandler.post { observer?.let(lifecycle::removeObserver) }
            }
        }
    }
}
