package com.forge.os.data.git

import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import timber.log.Timber
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase S — JGit-backed runner for the agent's `git_*` tools.
 *
 * Repos always live under the workspace; `path` is normalised through
 * [SandboxManager] so the agent can't reach outside the sandbox.
 *
 * HTTPS-only authentication. Tokens are pulled (in order):
 *   1. an explicit `token` arg supplied by the agent,
 *   2. memory under key `git_credentials/<host>` (set by the user once),
 *   3. ad-hoc `request_user_input` is the caller's responsibility.
 *
 * SSH, submodules and LFS are intentionally out of scope.
 */
@Singleton
class GitRunner @Inject constructor(
    private val sandboxManager: SandboxManager,
    private val memoryManager: MemoryManager,
) {
    suspend fun init(path: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            dir.mkdirs()
            Git.init().setDirectory(dir).call().use {
                "✅ git init at ${rel(dir)}"
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun status(path: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                val s = git.status().call()
                buildString {
                    appendLine("📂 ${rel(dir)} — branch ${git.repository.branch}")
                    if (s.isClean) { append("  clean"); return@withContext toString() }
                    if (s.added.isNotEmpty())      appendLine("  added:     " + s.added.joinToString(", "))
                    if (s.changed.isNotEmpty())    appendLine("  changed:   " + s.changed.joinToString(", "))
                    if (s.removed.isNotEmpty())    appendLine("  removed:   " + s.removed.joinToString(", "))
                    if (s.untracked.isNotEmpty())  appendLine("  untracked: " + s.untracked.joinToString(", "))
                    if (s.modified.isNotEmpty())   appendLine("  modified:  " + s.modified.joinToString(", "))
                    if (s.missing.isNotEmpty())    appendLine("  missing:   " + s.missing.joinToString(", "))
                    if (s.conflicting.isNotEmpty())appendLine("  conflict:  " + s.conflicting.joinToString(", "))
                }
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun add(path: String, pattern: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                git.add().addFilepattern(pattern).call()
                "✅ git add $pattern"
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun commit(
        path: String,
        message: String,
        authorName: String?,
        authorEmail: String?,
        addAll: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                if (addAll) git.add().addFilepattern(".").call()
                val name = authorName ?: "Forge"
                val email = authorEmail ?: "agent@forge.local"
                val rev = git.commit()
                    .setAuthor(PersonIdent(name, email))
                    .setCommitter(PersonIdent(name, email))
                    .setMessage(message)
                    .call()
                "✅ ${rev.name.take(7)} — $message"
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun log(path: String, max: Int): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                val n = max.coerceIn(1, 200)
                // git.log() throws NoHeadException on a freshly-init'd repo
                // with no commits yet — treat that as a friendly empty result
                // instead of letting the exception propagate and crash the app.
                val commits = try {
                    git.log().setMaxCount(n).call().toList()
                } catch (_: NoHeadException) {
                    return@withContext "(no commits yet — run git_commit first)"
                } catch (_: RefNotFoundException) {
                    return@withContext "(no commits yet — run git_commit first)"
                }
                if (commits.isEmpty()) return@withContext "(no commits yet)"
                buildString {
                    for (c in commits) {
                        appendLine("${c.name.take(7)}  ${c.shortMessage}  — ${c.authorIdent.name}")
                    }
                }
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun diff(path: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                val out = java.io.ByteArrayOutputStream()
                git.diff().setOutputStream(out).call()
                val s = out.toString(Charsets.UTF_8)
                if (s.isBlank()) "(no unstaged changes)" else s.take(20_000)
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun branch(path: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                val cur = git.repository.branch
                val all = git.branchList().call().map { it.name.removePrefix("refs/heads/") }
                buildString {
                    appendLine("current: $cur")
                    if (all.isEmpty()) appendLine("  (no branches yet — run git_commit first to create one)")
                    for (b in all) appendLine(if (b == cur) "* $b" else "  $b")
                }
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    /** Map JGit's noisy exception types to short, user-friendly strings. */
    private suspend fun friendlyError(t: Throwable, dir: File): String = when (t) {
        is RepositoryNotFoundException ->
            "❌ Not a git repository: ${rel(dir)} (run git_init first)"
        is NoHeadException, is RefNotFoundException ->
            "(no commits yet — run git_commit first)"
        else -> {
            Timber.w(t, "git operation failed at ${rel(dir)}")
            "❌ git: ${t.message ?: t::class.java.simpleName}"
        }
    }

    suspend fun checkout(path: String, branch: String, create: Boolean): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                git.checkout().setCreateBranch(create).setName(branch).call()
                "✅ checked out $branch"
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun remoteSet(path: String, name: String, url: String): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                val cfg = git.repository.config
                cfg.setString("remote", name, "url", url)
                cfg.save()
                "✅ remote $name → $url"
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun clone(url: String, intoPath: String, token: String?): String = withContext(Dispatchers.IO) {
        val dir = resolve(intoPath)
        runCatching {
            if (dir.exists() && dir.list()?.isNotEmpty() == true) {
                return@runCatching "❌ destination not empty: ${rel(dir)}"
            }
            dir.mkdirs()
            val cmd = Git.cloneRepository().setURI(url).setDirectory(dir)
            credsFor(url, token)?.let { cmd.setCredentialsProvider(it) }
            cmd.call().use { "✅ cloned into ${rel(dir)}" }
        }.getOrElse { friendlyError(it, dir) }
    }

    suspend fun push(path: String, remote: String, branch: String?, token: String?): String =
        withContext(Dispatchers.IO) {
            val dir = resolve(path)
            runCatching {
                Git.open(dir).use { git ->
                    val url = git.repository.config.getString("remote", remote, "url")
                        ?: return@runCatching "❌ remote '$remote' has no URL"
                    val cmd = git.push().setRemote(remote)
                    if (!branch.isNullOrBlank()) cmd.setRefSpecs(org.eclipse.jgit.transport.RefSpec(branch))
                    credsFor(url, token)?.let { cmd.setCredentialsProvider(it) }
                    val results = cmd.call().toList()
                    buildString {
                        appendLine("✅ pushed to $remote")
                        for (r in results) for (u in r.remoteUpdates) appendLine("  ${u.remoteName}: ${u.status}")
                    }
                }
            }.getOrElse { friendlyError(it, dir) }
        }

    suspend fun pull(path: String, remote: String, token: String?): String = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        runCatching {
            Git.open(dir).use { git ->
                val url = git.repository.config.getString("remote", remote, "url")
                    ?: return@runCatching "❌ remote '$remote' has no URL"
                val cmd = git.pull().setRemote(remote)
                credsFor(url, token)?.let { cmd.setCredentialsProvider(it) }
                val res = cmd.call()
                "✅ pull: success=${res.isSuccessful}; merge=${res.mergeResult?.mergeStatus}"
            }
        }.getOrElse { friendlyError(it, dir) }
    }

    private suspend fun resolve(path: String): File {
        // Reuse SandboxManager normalisation by going through mkdir for missing
        // dirs, otherwise resolve directly. We avoid touching internal members.
        val ws = File(sandboxManager.getWorkspacePath()).canonicalFile
        var clean = path.trim().trimStart('/')
        if (clean.startsWith("workspace/")) clean = clean.removePrefix("workspace/")
        val resolved = File(ws, clean).canonicalFile
        if (!resolved.absolutePath.startsWith(ws.absolutePath)) {
            throw SecurityException("Path escapes workspace: $path")
        }
        return resolved
    }

    private suspend fun rel(file: File): String {
        val ws = File(sandboxManager.getWorkspacePath()).canonicalFile
        return file.toRelativeString(ws).ifBlank { "." }
    }

    private fun credsFor(url: String, token: String?): UsernamePasswordCredentialsProvider? {
        val effectiveToken = token?.takeIf { it.isNotBlank() } ?: tokenFromMemory(url)
        if (effectiveToken.isNullOrBlank()) return null
        // For GitHub a username of "x-access-token" or any non-empty string works
        // when the password is a personal access token; same is broadly compatible
        // with GitLab and Bitbucket app passwords.
        return UsernamePasswordCredentialsProvider("x-access-token", effectiveToken)
    }

    private fun tokenFromMemory(url: String): String? {
        val host = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return null
        return try {
            memoryManager.recallByKey("git_credentials/$host")?.content
        } catch (e: Exception) {
            Timber.w(e, "tokenFromMemory failed for $host")
            null
        }
    }
}
