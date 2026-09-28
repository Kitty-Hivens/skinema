package dev.hivens.skinema.compose

/**
 * How long a window may take to answer for a frame and still be taken for one
 * somebody can see.
 *
 * A window on screen answers within a refresh or two, a few milliseconds to
 * a few tens of them. Measured, an XWayland window on a Hyprland workspace
 * that is not on screen answers about once a second. Read off Skiko's Metal
 * renderer rather than measured, a macOS window behind others waits up to
 * 300 ms between frames. This sits between the two with room on both sides.
 */
internal const val FRAME_SLOW_NANOS = 200_000_000L

/**
 * How long answers have to stay slow, without a break, before the window is
 * taken for one nobody can see. The same two seconds the player's own
 * mailbox notice waits, and for its reason: a machine that stutters for a
 * moment is not a window that went away.
 */
internal const val HIDDEN_AFTER_NANOS = 2_000_000_000L

/**
 * Quick answers in a row that bring a hidden window back. More than one,
 * because a hidden window can still answer quickly once in a while (a redraw
 * something else asked for), and a window really coming back answers at its
 * display's rate, so three take a few milliseconds.
 */
internal const val FAST_FRAMES_TO_RETURN = 3

/** How often a request nobody has answered yet is looked at. */
internal const val SIGHT_CHECK_MILLIS = 250L

/**
 * Whether a window is in front of anyone, judged by how long it takes to
 * answer for a frame.
 *
 * Nothing the toolkit hands over says so. Measured under XWayland on
 * Hyprland, a window whose workspace is not on screen, one behind another
 * that has gone fullscreen, and one in a hidden special workspace all keep
 * isShowing true and their lifecycle where it was, and none gets an X11
 * hidden state. A window moved to another workspace even keeps its focus.
 * What changes in every one of them is how fast frames come back: from a
 * refresh or two to about one a second. Skiko's Metal renderer does the
 * same with the occlusion macOS reports. So the answer time is the signal,
 * and minimising, which Compose does report, is taken from the lifecycle
 * instead.
 *
 * Only answers the surface asked for count, and only while it keeps asking.
 * A slow answer after an idle stretch starts the count again, because the
 * slowness is only evidence while it is continuous: a paused player asks for
 * nothing, and the hiccup before the pause and the one after it are not two
 * seconds of a hidden window.
 *
 * Idle means as long as the verdict itself waits, not as long as one slow
 * answer. The first version used the shorter bound and never fired on the
 * window it was written for: measured under XWayland on Hyprland, the thread
 * that answers also presents, and it sits in the buffer swap for the same
 * second the answer took, so every request went out a second after the last
 * answer and each one looked like the start of a new count.
 *
 * Read and written from the composition's coroutines, which a desktop
 * application runs on one thread and a headless scene may not, hence the
 * locking.
 */
internal class SightTracker(
    private val slowNanos: Long = FRAME_SLOW_NANOS,
    private val hiddenAfterNanos: Long = HIDDEN_AFTER_NANOS,
    private val fastFramesToReturn: Int = FAST_FRAMES_TO_RETURN,
) {

    /** Whether the window is taken to be in front of anyone. */
    @get:Synchronized
    var inSight = true
        private set

    private var askedAt = NONE
    private var answeredAt = NONE
    private var slowSince = NONE
    private var fastRun = 0
    private var reported: Boolean? = null

    /**
     * A frame was asked for at [now]. A second request while one is
     * outstanding waits for the same frame, so the earlier time stands.
     */
    @Synchronized
    fun asked(now: Long) {
        if (askedAt != NONE) return
        if (answeredAt != NONE && now - answeredAt >= hiddenAfterNanos) slowSince = NONE
        askedAt = now
    }

    /** The frame asked for has come, at [now]. */
    @Synchronized
    fun answered(now: Long) {
        val asked = askedAt
        if (asked == NONE) return
        askedAt = NONE
        answeredAt = now
        if (now - asked < slowNanos) {
            slowSince = NONE
            fastRun++
            if (fastRun >= fastFramesToReturn) inSight = true
        } else {
            fastRun = 0
            if (slowSince == NONE) slowSince = asked
            if (now - slowSince >= hiddenAfterNanos) inSight = false
        }
    }

    /**
     * Looks at a request still waiting at [now]. A window whose frame clock
     * has stopped altogether never answers, and without this it would never
     * be judged at all.
     */
    @Synchronized
    fun check(now: Long) {
        val asked = askedAt
        if (asked == NONE || now - asked < slowNanos) return
        fastRun = 0
        if (slowSince == NONE) slowSince = asked
        if (now - slowSince >= hiddenAfterNanos) inSight = false
    }

    /**
     * What to tell the player, or null when that is what it was told last.
     * [started] is the lifecycle's half: a minimised window is out of sight
     * whatever its frames say.
     */
    @Synchronized
    fun verdict(started: Boolean): Boolean? {
        val now = inSight && started
        if (now == reported) return null
        reported = now
        return now
    }

    /**
     * The report was withdrawn, so the next [verdict] is news whatever it
     * says. Without this a surface whose effect restarts would never tell the
     * player again what it had already told it once.
     */
    @Synchronized
    fun withdrawn() {
        reported = null
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
