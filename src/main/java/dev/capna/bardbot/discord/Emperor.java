package dev.capna.bardbot.discord;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.util.Objects;

/**
 * Who may strike a writ task.
 *
 * <p>A second gate beside {@link Tribunal} rather than a flag on it, because the two are not a
 * hierarchy. Striking a task is the one thing on the bot that overrules a tribunal member, and
 * that should be answerable by reading one short file rather than by working out which of several
 * roles counts as which.
 */
public final class Emperor {

    private final String roleId;

    public Emperor(String roleId) {
        this.roleId = Objects.requireNonNull(roleId, "roleId");
    }

    public boolean holds(Member member) {
        return member != null && member.getRoles().stream()
                .map(Role::getId)
                .anyMatch(roleId::equals);
    }

    /**
     * Refuses the interaction unless the caller is the Emperor.
     *
     * @return true when the command should carry on
     */
    public boolean check(IReplyCallback event) {
        if (holds(event.getMember())) {
            return true;
        }
        Replies.problem(event, "That is for the Emperor.");
        return false;
    }
}
