package com.tritonterm.android.terminal

import android.content.Context
import java.io.File

/** PRoot needs an existing, writable host directory for its glue rootfs and loader files. */
internal object ProotTempDirectory {
    fun forApp(context: Context): File = ensure(File(context.cacheDir, "proot"))

    fun ensure(directory: File): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Could not create PRoot temporary directory: ${directory.absolutePath}")
        }
        if (!directory.canWrite() || !directory.canExecute()) {
            throw IllegalStateException("PRoot temporary directory is not writable and searchable: ${directory.absolutePath}")
        }
        return directory
    }
}
