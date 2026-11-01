package dev.hivens.skinema.subtitles

/**
 * The latest rendered subtitle state, consumed like a frame: poll
 * [dev.hivens.skinema.player.VideoPlayer.acquireSubtitles] from any
 * render loop; null means nothing newer than what you already hold.
 * An overlay with empty [patches] is a CLEAR -- whatever was on screen
 * goes away. The consumer owns the returned slot until its next
 * acquire; the player never writes into it during that window.
 *
 * Coordinates live in the canvas space announced by [canvasWidth] x
 * [canvasHeight] (the subtitle render size -- the video's displayed
 * rect once a surface reports one, the storage size before that); map
 * them onto wherever the video lands on screen.
 */
class SubtitleOverlay internal constructor() {

    /** Bumps on every publish; cheap change detection for render loops. */
    var generation = 0L
        internal set

    var canvasWidth = 0
        internal set

    var canvasHeight = 0
        internal set

    var patches: List<SubtitlePatch> = emptyList()
        internal set

    // The slot-bound reusable patch: publishes into the same arrays a
    // consumer is NOT holding (TripleBuffer guarantees the writing slot
    // is private to the producer).
    internal val scratch = SubtitlePatch()
}

/**
 * One positioned overlay region: premultiplied RGBA at a tight stride
 * (width * 4). The array is reused across publishes of the same slot;
 * consumers that keep pixels must copy.
 */
class SubtitlePatch internal constructor() {
    var x = 0
        internal set
    var y = 0
        internal set
    var width = 0
        internal set
    var height = 0
        internal set
    var rgba: ByteArray = ByteArray(0)
        internal set
}
