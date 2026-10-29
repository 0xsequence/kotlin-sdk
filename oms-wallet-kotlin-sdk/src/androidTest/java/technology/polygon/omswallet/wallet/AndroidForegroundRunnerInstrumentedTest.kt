package technology.polygon.omswallet.wallet

import android.app.Activity
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** An empty activity whose start puts the test process in the foreground. */
class ForegroundTestActivity : Activity()

class AndroidForegroundRunnerInstrumentedTest {
    private val runner = AndroidForegroundRunner()

    @Before
    fun noActivityStarted() {
        // The process lifecycle reports stopped about 700 ms after the last activity stops.
        waitForProcessState { !it.isAtLeast(Lifecycle.State.STARTED) }
    }

    @Test
    fun waitsForAStartedActivityAndRunsOnTheMainThread() {
        val ran = CountDownLatch(1)
        var ranOnMainThread = false
        thread {
            runner.runInForeground {
                ranOnMainThread = Looper.myLooper() == Looper.getMainLooper()
                ran.countDown()
            }
        }.join()

        // No activity is started, as when a push message or a broadcast starts the process.
        assertFalse(ran.await(500, TimeUnit.MILLISECONDS))
        ActivityScenario.launch(ForegroundTestActivity::class.java).use {
            assertTrue(ran.await(5, TimeUnit.SECONDS))
        }
        assertTrue(ranOnMainThread)
    }

    @Test
    fun runsRightAwayWhenTheAppIsAlreadyInTheForeground() {
        ActivityScenario.launch(ForegroundTestActivity::class.java).use {
            waitForProcessState { it.isAtLeast(Lifecycle.State.STARTED) }
            val runs = mutableListOf<String>()

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runner.runInForeground { runs += "main" }
                // On the main thread it runs before runInForeground returns.
                assertEquals(listOf("main"), runs)
            }
            val ranFromBackground = CountDownLatch(1)
            thread { runner.runInForeground { ranFromBackground.countDown() } }.join()
            assertTrue(ranFromBackground.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancelledWorkNeverRuns() {
        val ran = CountDownLatch(1)
        val cancel = runner.runInForeground { ran.countDown() }

        cancel()
        ActivityScenario.launch(ForegroundTestActivity::class.java).use {
            assertFalse(ran.await(1, TimeUnit.SECONDS))
        }
    }

    private fun waitForProcessState(condition: (Lifecycle.State) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            var state = Lifecycle.State.INITIALIZED
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                state = ProcessLifecycleOwner.get().lifecycle.currentState
            }
            if (condition(state)) return
            check(System.nanoTime() < deadline) { "Process lifecycle stayed $state" }
            Thread.sleep(50)
        }
    }
}
