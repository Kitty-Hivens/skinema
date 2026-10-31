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
 * It became the default after [Freeze], which a consumer wanting the old
 * behaviour passes by name.
 *
 * It decides one thing only: whether the clock stops. Either way the player
 * stops decoding and converting frames nobody is taking, which is the point
 * of noticing at all.
 */
enum class WhenUnwatched {

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
     * The rejoin moves the picture alone, and the sound is left exactly where
     * it is: someone who went on listening would otherwise hear a stretch a
     * second time. A picture less than a couple of seconds behind decodes
     * forward to the clock. One further behind jumps to the keyframe before
     * the clock and catches up from there, showing a frame now and then, and
     * never a frame older than the one already on screen.
     *
     * The lap still turns while nobody watches, read off the file's declared
     * duration. A looping file whose time runs out starts over, sound
     * included. One that does not loop ends, and when the picture is wanted
     * again it still shows the frame it had when it was hidden, since nothing
     * decoded the end. A source that declares no duration cannot tell when
     * its lap is over, and turns it only once the picture comes back.
     */
    KeepTime,

    /**
     * Time runs on while the player can be heard, and stops while it cannot.
     *
     * Heard means sound is going out through a device at a player volume above
     * zero. A player with no audio, one at volume zero, and one whose track
     * has ended under a longer picture take [Freeze]. The rest take
     * [KeepTime]: the sound plays on, and the picture catches up with it when
     * it is wanted again. What happens past the player is not seen: a sink
     * that discards the sound, or a system mixer muted for this stream, still
     * counts as heard.
     *
     * Asked once, at the moment the picture stops being taken, and not again
     * until it is taken and stopped once more. Unmuting a hidden frozen
     * player leaves it frozen, and muting a hidden playing one leaves it
     * playing, since neither changes whether anyone is looking. A fade-in that
     * starts from zero is silent at that moment, so a window hidden before the
     * fade has begun pauses.
     */
    FollowSound,
}
