package com.jcwear.coach

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plan T4: the known demo literals must not come back into the app code. */
class NoFakeValuesTest {
    private val banned = listOf(
        "Morning, Alex", "Last synced 6 min ago", "Last backup today, 06:42", "23:10 – 06:30",
        "buildNinetyDaySeries", "?: 84", "?: 52", "?: 66", "?: 73.0", "v0.0.2.3", "Ready (J2208A2",
        "score = 85,", "score = 88,", "Lowest in 2 weeks", "\"↑ 8% vs normal\"", "series90[81]",
        "Preview SpO2 alert", "Your recovery hit 86"
    )

    @Test
    fun noDemoLiteralsInMainSources() {
        val hits = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            banned.filter { it in text }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("Demo values found: $hits", hits.isEmpty())
    }
}
