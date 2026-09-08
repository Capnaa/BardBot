package dev.capna.bardbot.discord;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.util.List;
import java.util.Objects;

/**
 * Who may award virtue and administer houses.
 *
 * <p>One gate, used by every privileged command, so who can do what is answerable by reading a
 * single file rather than by auditing each command in turn. It is deliberately not Discord's own
 * permission system: being able to manage a Discord server and being on the tribunal are different
 * things, and the fiction should not be administered by whoever happens to hold Manage Server.
 */
public final class Tribunal {

    private final List<String> roleIds;

    public Tribunal(List<String> roleIds) {
        this.roleIds = List.copyOf(Objects.requireNonNull(roleIds, "roleIds"));
    }

    public boolean holds(Member member) {
        return member != null && member.getRoles().stream()
                .map(Role::getId)
                .anyMatch(roleIds::contains);
    }

    /**
     * Refuses the interaction unless the caller is on the tribunal.
     *
     * <p>The refusal names no role and lists nobody. Someone who is not on the tribunal has no use
     * for the membership list, and a refusal that enumerates who to go and ask is an invitation to
     * go and pester them.
     *
     * @return true when the command should carry on
     */
    public boolean check(IReplyCallback event) {
        if (holds(event.getMember())) {
            return true;
        }
        Replies.problem(event, "That is for the tribunal.");
        return false;
    }
}
