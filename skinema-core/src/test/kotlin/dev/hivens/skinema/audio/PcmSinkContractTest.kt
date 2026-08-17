package dev.hivens.skinema.audio

import dev.hivens.skinema.libav.Fixtures
import org.junit.jupiter.api.BeforeEach

/**
 * [PcmSinkContract] against every sink the project has, doubles included.
 *
 * The doubles are what the whole audio half of the player is proven against,
 * so a double that has drifted from a real line proves the wrong thing. Each
 * one differs from a line in what it makes controllable -- when the playhead
 * moves -- and in nothing else; that is what [advance] overrides say.
 */

/** Instant writes, everything written counted as played. */
class FakePcmSinkContractTest : PcmSinkContract() {
    override fun newSink(): PcmSink = FakePcmSink()

    // Nothing to wait for: this sink plays a write the moment it accepts it.
    override fun advance(sink: PcmSink, frames: Long) = Unit
}

/** Blocking writes over a bounded buffer, playhead moved by hand. */
class BoundedPcmSinkContractTest : PcmSinkContract() {
    // Room for the suite's half-second write, or the very first one parks
    // against a device nothing is draining.
    override fun newSink(): PcmSink = BoundedPcmSink(capacityFrames = sampleRate.toLong())

    override fun advance(sink: PcmSink, frames: Long) {
        (sink as BoundedPcmSink).consume(frames)
    }
}

/** Blocking writes, playhead on the wall clock -- the model of a real line. */
class PacedPcmSinkContractTest : PcmSinkContract() {
    override fun newSink(): PcmSink = PacedPcmSink(bufferFrames = sampleRate.toLong())
}

/**
 * The real thing. Opt-in: a headless runner has no sound device, and a suite
 * that quietly skips its hardware reads exactly like one that passed. Named in
 * SKINEMA_REQUIRE_CAPS, absence is a loud failure instead.
 */
class JavaSoundSinkContractTest : PcmSinkContract() {

    @BeforeEach
    fun gate() = Fixtures.assumeAudioDevice()

    override fun newSink(): PcmSink = JavaSoundSink()
}
