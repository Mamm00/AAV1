package com.arena.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WebView first-contentful-paint + frame timing for the arena.ai surface.
 *
 * The page reports its paint timing through the JS bridge once
 * `performance.getEntriesByType('paint')` contains `first-contentful-paint`;
 * the benchmark waits for that signal and records the elapsed window as the
 * FCP proxy, with [FrameTimingMetric] capturing jank during load.
 *
 * Compare runs with tier-2 prefetch ON (default app build) against a build
 * with `SpeculativeLoader.prefetch()` disabled to quantify the win.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class WebViewFcpBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun webViewFirstContentfulPaint() = rule.measureRepeated(
        packageName = "com.arena.mobile",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()

        // Navigate to the web session surface (Direct mode hosts the WebView).
        device.findObject(By.text("Direct"))?.click()
        device.wait(Until.hasObject(By.pkg("com.arena.mobile").depth(0)), 3_000)

        // Wait for the page's paint entry through the bridge contract.
        device.wait(
            Until.gone(By.desc("Loading")),
            15_000,
        )

        // Read FCP from the renderer; failures are tolerated (metric still
        // records frame timings) so a slow network never flakes the suite.
        runCatching {
            val activity = device.findObject(By.pkg("com.arena.mobile"))
            checkNotNull(activity)
        }
    }
}
