package dev.capna.bardbot.model;

/**
 * How a writ task stands.
 *
 * <p>Only {@link #OPEN} is a state something can still happen to. The other three are how it
 * ended, kept so the record says which rather than just that it did.
 */
public enum WritStatus {

    /** Still on the member it was served on. */
    OPEN,

    /** They did it, and the writ became theirs. */
    COMPLETED,

    /** Whoever served it took it back, and the writ with it. */
    CANCELLED,

    /** The Emperor threw it out as unreasonable. The writ went back to whoever served it. */
    STRUCK
}
