package dev.hivens.skinema.player

/**
 * What the timeline does while nobody is taking the picture.
 *
 * The two fixed answers belong to two different things a player can be. A
 * background -- a wallpaper, a menu backdrop -- is either being looked at or
 * it is not, and when it comes back the viewer carries on from where the
 * picture stopped: nothing was missed, because nothing was being watched. A
 * live source is the other case. The file went on without the viewer, and
 * what should come back is the current picture rather than a replay of the
 * gap, which means time has to keep running while nobody is looking.
 *
 * Most players are neither, and what tells them apart is the sound. A window
 * put behind another while its music plays is still being listened to, and
 * stopping it would stop the part the person is using. A silent one behind
 * another is doing nothing for anybody. [FollowSound], the default, asks.
 *
 * It decides one thing only: whether the clock stops. Either way the player
 * stops decoding and converting frames nobody is taking, which is the point
 * of noticing at all.
 */
enum class WhenUnwatched {

    /**
     * Time runs on while the player can be heard, and stops while it cannot.
     *
     * Heard means sound is playing through a device at a volume above zero. A
     * player with no audio, a muted one, and one whose track has ended under
     * a longer picture take [Freeze]. The rest take [KeepTime]: the sound
     * plays on, and the picture catches up with it when it is wanted again.
     *
     * Asked once, at the moment the picture stops being taken, and not again
     * until it is taken and stopped once more. Unmuting a hidden frozen
     * player leaves it frozen, and muting a hidden playing one leaves it
     * playing, since neither changes whether anyone is looking.
     */
    FollowSound,

    /**
     * Time stops with the picture and resumes where it stopped.
     *
     * The stop is a real pause, so [VideoPlayer.state] reads
     * [VideoPlayer.State.Paused] for as long as it lasts -- a consumer
     * watching state sees a pause it never asked for. Being wanted again lifts
     * it. A pause the consumer DID ask for is never lifted this way: it
     * outlives the picture being wanted again.
     */
    Freeze,

    /**
     * Time runs on, and the picture rejoins it wherever it has got to.
     *
     * The rejoin moves the picture alone. The decoder jumps to the keyframe
     * before the clock and decodes forward from there, showing a frame now
     * and then while it catches up, and the sound is left exactly where it
     * is: someone who went on listening would otherwise hear the stretch
     * since that keyframe a second time.
     *
     * The lap still turns while nobody watches. A looping file whose time runs
     * out starts over, sound included, and one that does not loop ends.
     */
    KeepTime,
}
