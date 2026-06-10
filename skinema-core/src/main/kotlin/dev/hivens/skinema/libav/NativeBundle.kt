package dev.hivens.skinema.libav

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Deploys the bundled libav* natives from the classpath to a per-user
 * cache so [Libav] can load them by absolute path -- the packaging
 * decision from ROADMAP.md section 10: per-platform classifier jars carry
 * the trimmed FFmpeg under `dev/hivens/skinema/natives/<platform>/` plus
 * an `index.txt` whose first line is the bundle fingerprint and the rest
 * the file list (a jar cannot enumerate its own resources).
 *
 * Extraction is crash- and race-safe: files land in a temp directory that
 * is atomically renamed to the fingerprint directory; a loser of that
 * race (another process, a previous crash) just reuses the winner's copy.
 * The fingerprint changes with the bundle contents, so upgrades deploy
 * fresh and never overwrite libraries another process may have mapped.
 */
object NativeBundle {

    private const val RESOURCE_ROOT = "dev/hivens/skinema/natives"

    /** Bundle for the current platform, deployed to the default cache; null when not on the classpath. */
    fun deployIfBundled(): Path? =
        deploy(NativeBundle::class.java.classLoader, defaultCacheRoot(), nativesPlatform())

    internal fun deploy(loader: ClassLoader, cacheRoot: Path, platform: String): Path? {
        val root = "$RESOURCE_ROOT/$platform"
        val index = loader.getResource("$root/index.txt") ?: return null
        val lines = index.readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "natives index for $platform is empty" }
        val fingerprint = lines.first()
        val files = lines.drop(1)
        require(files.isNotEmpty()) { "natives index for $platform lists no files" }

        val target = cacheRoot.resolve(fingerprint)
        if (Files.isDirectory(target)) return target

        Files.createDirectories(cacheRoot)
        val staging = Files.createTempDirectory(cacheRoot, ".deploy-")
        try {
            for (file in files) {
                require(!file.contains("..")) { "natives index entry escapes the bundle: $file" }
                val out = staging.resolve(file)
                Files.createDirectories(out.parent)
                val resource = loader.getResourceAsStream("$root/$file")
                    ?: error("natives index lists $file but the bundle does not carry it")
                resource.use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            // Another process deployed the same fingerprint first; use theirs.
            staging.toFile().deleteRecursively()
        } catch (t: Throwable) {
            staging.toFile().deleteRecursively()
            throw t
        }
        return target
    }

    internal fun defaultCacheRoot(): Path {
        val home = System.getProperty("user.home")
        val base = when (Os.current()) {
            Os.WINDOWS -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) } ?: Path.of(home, "AppData", "Local")
            Os.MAC -> Path.of(home, "Library", "Caches")
            Os.LINUX -> System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) } ?: Path.of(home, ".cache")
        }
        return base.resolve("skinema").resolve("natives")
    }
}
