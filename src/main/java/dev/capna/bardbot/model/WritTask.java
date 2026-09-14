package dev.capna.bardbot.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * A writ spent on another tribunal member, from being served to however it ended.
 *
 * <p>Kept forever, like an award. A task that has been completed or struck is a record of what
 * happened, not a queue entry to be thrown away once it has been dealt with, and when there is an
 * argument about whether something was ever done this is what settles it.
 *
 * <p>Public tasks are not here. Those live in the forum as posts, and the writ spent on one is
 * simply gone.
 *
 * @param id        a stable identity. The number a person types is a position in their list of
 *                  open tasks and can point at a different task the next time one closes, so it
 *                  is never stored.
 * @param servedBy  the tribunal member who spent the writ
 * @param servedOn  the tribunal member the task is on
 * @param task      what they were asked to do
 * @param dueDate   when it is due, as a date rather than an instant, because nobody serves a writ
 *                  due at a particular minute
 * @param createdAt when the writ was used
 * @param closedAt  when it stopped being open, absent while it still is
 * @param coords    submitted on completion, if something was built or gathered
 * @param link      submitted on completion, if it was a document or a video
 * @param struckBy  the Emperor who struck it, absent unless it was
 */
public record WritTask(int id,
                       String servedBy,
                       String servedOn,
                       String task,
                       LocalDate dueDate,
                       Instant createdAt,
                       WritStatus status,
                       Optional<Instant> closedAt,
                       Optional<String> coords,
                       Optional<String> link,
                       Optional<String> struckBy) {

    /** Long enough to say what is wanted, short enough to read in a list of them. */
    public static final int MAX_TASK = 500;

    /** Coords or a link, which nobody needs a paragraph for. */
    public static final int MAX_SUBMISSION = 300;

    public boolean isOpen() {
        return status == WritStatus.OPEN;
    }

    /** The same task, closed. Every way a task ends goes through here so none of them forget a field. */
    public WritTask closed(WritStatus how, Instant when, Optional<String> coords,
                           Optional<String> link, Optional<String> struckBy) {
        return new WritTask(id, servedBy, servedOn, task, dueDate, createdAt, how,
                Optional.of(when), coords, link, struckBy);
    }
}
