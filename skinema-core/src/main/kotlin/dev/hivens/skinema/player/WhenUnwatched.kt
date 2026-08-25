package dev.hivens.skinema.player

/**
 * What the timeline does while nobody is taking the picture.
 *
 * The two answers belong to two different things a player can be, and no
 * default is right for both. A background -- a wallpaper, a menu backdrop --
 * is either being looked at or it is not, and when it comes back the viewer
 * carries on from where the picture stopped: nothing was missed, because
 * nothing was being watched. A live source is the other case. The file went
 * on without the viewer, and what should come back is the current picture
 * rather than a replay of the gap, which means time has to keep running
 * while nobody is looking.
 *
 * It decides one thing only: whether the clock stops. Either way the player
 * stops decoding and converting frames nobody is taking, which is the point
 * of noticing at all.
 */
enum class WhenUnwatched {

    /** Time stops with the picture and resumes where it stopped. */
    Freeze,

    /** Time runs on; the picture rejoins it wherever it has got to. */
    KeepTime,
}
