package com.tritonterm.android.terminal

import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotTempDirectoryTest {
    @Test
    fun createsMissingHostTempDirectoryAndReusesItWithoutCleanup() {
        val parent = Files.createTempDirectory("triton-proot-cache").toFile()
        try {
            val missing = parent.resolve("cache/proot")
            assertTrue("reproduce Android cache eviction / never-created directory", !missing.exists())

            val result = ProotTempDirectory.ensure(missing)

            assertEquals(missing.canonicalFile, result.canonicalFile)
            assertTrue(result.isDirectory)
            assertTrue(result.canWrite())
            assertTrue(result.canExecute())
            assertEquals(result.canonicalFile, ProotTempDirectory.ensure(missing).canonicalFile)
            assertTrue("the shared temp directory must not be deleted after one process", result.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun concurrentProotLaunchesCanInitializeTheSameMissingTempDirectory() {
        val parent = Files.createTempDirectory("triton-proot-race").toFile()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val missing = parent.resolve("cache/proot")
            val results = (1..8).map {
                executor.submit<java.io.File> { ProotTempDirectory.ensure(missing) }
            }.map { it.get() }

            assertTrue(missing.isDirectory)
            assertTrue(results.all { it.canonicalFile == missing.canonicalFile })
        } finally {
            executor.shutdownNow()
            parent.deleteRecursively()
        }
    }
}
