package dev.capna.bardbot.ops;

/**
 * A part of the bot that the tribunal can switch off without a redeploy.
 *
 * <p>Waiting on a code change and a deploy to retire a command that has started causing arguments
 * is the wrong answer, so the enabled set is data.
 *
 * <p>Turning a feature off never discards its data. Virtue survives the virtue toggle, so it can be
 * switched back on without anybody's score having moved.
 */
public enum Feature {
    PROFILES,
    VIRTUE,
    HOUSES,
    TITLES
}
