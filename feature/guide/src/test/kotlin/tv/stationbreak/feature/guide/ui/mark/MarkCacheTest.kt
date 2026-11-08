// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class FakeBitmap : ImageBitmap {
    override val width = 64
    override val height = 64
    override val config = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = true

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit

    override fun prepareToDraw() = Unit
}

private open class FakeLoader(
    private val known: Set<String>,
) : MarkLoader {
    val loadCalls = ConcurrentHashMap<String, Int>()
    val threads = ConcurrentHashMap<String, Thread>()
    var keysThread: Thread? = null
    var result: (String) -> ImageBitmap? = { FakeBitmap() }

    override fun keys(): Set<String> {
        keysThread = Thread.currentThread()
        return known
    }

    override fun load(key: String): ImageBitmap? {
        loadCalls.merge(key, 1, Int::plus)
        threads[key] = Thread.currentThread()
        return result(key)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MarkCacheTest {
    private fun TestScope.cacheOf(loader: MarkLoader) = MarkCache(loader, this, StandardTestDispatcher(testScheduler))

    @Test
    fun peekIsNullBeforeAnythingLoads() =
        runTest {
            val cache = cacheOf(FakeLoader(setOf("a")))
            assertNull(cache.peek("a"))
            cache.request(listOf("a"))
            assertNull(cache.peek("a"))
            assertEquals(0, cache.arrivals.value)
        }

    @Test
    fun requestLoadsOnTheIoDispatcherNotTheCaller() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val loader = FakeLoader(setOf("a"))
            val cache = MarkCache(loader, scope, executor.asCoroutineDispatcher())
            val caller = Thread.currentThread()
            cache.request(listOf("a"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (cache.peek("a") == null && System.nanoTime() < deadline) Thread.sleep(5)
            assertNotNull(cache.peek("a"))
            assertNotSame(caller, loader.threads["a"])
            assertEquals("fake-io", loader.threads["a"]?.name)
            assertEquals("fake-io", loader.keysThread?.name)
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun requestDoesNothingOnTheCallerUntilTheDispatcherRuns() =
        runTest {
            val loader = FakeLoader(setOf("a"))
            val cache = cacheOf(loader)
            cache.request(listOf("a"))
            assertTrue(loader.loadCalls.isEmpty())
            assertNull(loader.keysThread)
            advanceUntilIdle()
            assertEquals(1, loader.loadCalls["a"])
        }

    @Test
    fun aSecondRequestForALoadedKeyDoesNotLoadAgain() =
        runTest {
            val loader = FakeLoader(setOf("a"))
            val cache = cacheOf(loader)
            cache.request(listOf("a"))
            advanceUntilIdle()
            val first = cache.peek("a")
            cache.request(listOf("a"))
            advanceUntilIdle()
            assertEquals(1, loader.loadCalls["a"])
            assertSame(first, cache.peek("a"))
        }

    @Test
    fun aSecondRequestWhileLoadingDoesNotLoadTwice() {
        val executor = Executors.newFixedThreadPool(2)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val loader =
                FakeLoader(setOf("a")).also {
                    it.result = {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        FakeBitmap()
                    }
                }
            val cache = MarkCache(loader, scope, executor.asCoroutineDispatcher())
            cache.request(listOf("a"))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.request(listOf("a"))
            cache.request(listOf("a", "a"))
            Thread.sleep(100)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (cache.peek("a") == null && System.nanoTime() < deadline) Thread.sleep(5)
            Thread.sleep(100)
            assertEquals(1, loader.loadCalls["a"])
            assertEquals(1, cache.arrivals.value)
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun anUnknownKeyIsIgnoredAndStaysNull() =
        runTest {
            val loader = FakeLoader(setOf("a"))
            val cache = cacheOf(loader)
            cache.request(listOf("nope", "../x"))
            advanceUntilIdle()
            assertNull(cache.peek("nope"))
            assertTrue(loader.loadCalls.isEmpty())
            assertEquals(0, cache.arrivals.value)
        }

    @Test
    fun aLoaderThatReturnsNullOrThrowsLeavesInitialsAndIsNotRetriedInALoop() =
        runTest {
            val loader = FakeLoader(setOf("null", "boom", "ok"))
            loader.result = {
                when (it) {
                    "null" -> null
                    "boom" -> error("bad png")
                    else -> FakeBitmap()
                }
            }
            val cache = cacheOf(loader)
            repeat(3) {
                cache.request(listOf("null", "boom", "ok"))
                advanceUntilIdle()
            }
            assertNull(cache.peek("null"))
            assertNull(cache.peek("boom"))
            assertNotNull(cache.peek("ok"))
            assertEquals(1, loader.loadCalls["null"])
            assertEquals(1, loader.loadCalls["boom"])
            assertEquals(1, loader.loadCalls["ok"])
            assertEquals(1, cache.arrivals.value)
        }

    @Test
    fun arrivalsCountsEachArrivalOnce() =
        runTest {
            val cache = cacheOf(FakeLoader(setOf("a", "b", "c")))
            assertEquals(0, cache.arrivals.value)
            cache.request(listOf("a", "b"))
            advanceUntilIdle()
            assertEquals(2, cache.arrivals.value)
            cache.request(listOf("a", "b", "c"))
            advanceUntilIdle()
            assertEquals(3, cache.arrivals.value)
        }

    @Test
    fun aLoadThatFinishesAfterTheCallerStoppedCaringIsHarmless() {
        val executor = Executors.newSingleThreadExecutor()
        val cacheScope = CoroutineScope(SupervisorJob())
        val callerJob = Job()
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val loader = FakeLoader(setOf("a"))
            loader.result = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                FakeBitmap()
            }
            val cache = MarkCache(loader, cacheScope, executor.asCoroutineDispatcher())
            // The caller asks, then goes away (its own scope is cancelled) while the decode is still running.
            CoroutineScope(callerJob).also { cache.request(listOf("a")) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            callerJob.cancel()
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (cache.peek("a") == null && System.nanoTime() < deadline) Thread.sleep(5)
            assertNotNull(cache.peek("a"))
            assertEquals(1, cache.arrivals.value)
        } finally {
            cacheScope.cancel()
            executor.shutdownNow()
        }
    }
}
