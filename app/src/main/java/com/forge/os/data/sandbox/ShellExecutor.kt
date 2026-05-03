package com.forge.os.data.sandbox

import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellExecutor @Inject constructor() {

    fun execute(command: String, workingDir: File, timeoutSeconds: Int): String {
        Timber.d("Executing shell: $command in ${workingDir.absolutePath}")
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Command timed out after ${timeoutSeconds}s")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.exitValue()
        return if (exitCode != 0) {
            "Exit code $exitCode\n$output".trim()
        } else {
            output.ifBlank { "(Command completed with no output)" }
        }
    }
}
