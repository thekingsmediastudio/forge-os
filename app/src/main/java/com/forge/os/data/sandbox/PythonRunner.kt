package com.forge.os.data.sandbox

import com.chaquo.python.PyException
import com.chaquo.python.Python
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PythonRunner @Inject constructor(
    private val python: Python
) {

    fun run(code: String, workingDir: File, profile: String = "default", timeoutSeconds: Int): String {
        Timber.d("Executing Python in ${workingDir.absolutePath} (profile=$profile)")
        return try {
            val module = python.getModule("forge_sandbox.python_runner")
            val result = module.callAttr(
                "run_python",
                code,
                workingDir.absolutePath,
                profile,
                timeoutSeconds
            )
            result.toString()
        } catch (e: PyException) {
            Timber.e(e, "Python execution failed")
            "Python Error: ${e.message}"
        } catch (e: Throwable) {
            // Chaquopy can throw non-PyException errors (UnsatisfiedLinkError,
            // missing module, OOM, etc.) that would otherwise crash the whole
            // process. Catch them here so the agent loop can show a friendly
            // tool result instead.
            Timber.e(e, "Python runner crashed")
            "Python Error: ${e::class.simpleName}: ${e.message ?: "unknown failure"}"
        }
    }
}
