package technology.polygon.omswallet.wallet

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
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
 * Runs now when the process is visible; otherwise once, when the first activity starts. Runs now
 * when visibility cannot be determined or the context is not an [Application].
 */
internal class AndroidForegroundRunner(
    context: Context,
) : ForegroundRunner {
    private val appContext: Context = context.applicationContext ?: context

    override fun runInForeground(action: () -> Unit): () -> Unit {
        val application = appContext as? Application
        if (application == null || isVisible()) {
            action()
            return {}
        }
        val ran = AtomicBoolean(false)
        val callbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (ran.compareAndSet(false, true)) {
                        application.unregisterActivityLifecycleCallbacks(this)
                        action()
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        application.registerActivityLifecycleCallbacks(callbacks)
        return {
            if (ran.compareAndSet(false, true)) application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun isVisible(): Boolean =
        runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }.getOrDefault(true)
}
