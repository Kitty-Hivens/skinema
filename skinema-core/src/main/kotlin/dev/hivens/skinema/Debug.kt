package dev.hivens.skinema

/**
 * Opt-in diagnostics for the fail-closed paths. A capability that loads
 * broken, or a session that dies mid-stream, otherwise reduces to a silent
 * skip indistinguishable from "genuinely absent" without attaching a
 * debugger. With SKINEMA_DEBUG set in the environment the swallowed cause
 * reaches stderr -- the fail-closed behaviour is unchanged, only its
 * visibility. (Seek tracing has its own narrower SKINEMA_DEBUG_SEEK flag.)
 */
internal object Debug {
    val enabled: Boolean = System.getenv("SKINEMA_DEBUG") != null

    /** Print [cause] under [context] when SKINEMA_DEBUG is set; otherwise a no-op. */
    fun trace(context: String, cause: Throwable) {
        if (enabled) {
            System.err.println("[skinema] $context: $cause")
            cause.printStackTrace()
        }
    }
}
