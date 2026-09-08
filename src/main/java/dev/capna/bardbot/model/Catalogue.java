package dev.capna.bardbot.model;

import java.util.List;

/**
 * Every title the bot knows about that is not a house's own.
 *
 * <p>Held in one file because both halves are the government's to change and neither is worth a
 * deploy. Noble titles are absent on purpose: those belong to the house that defined them and live
 * with it, so a house being removed takes its titles with it.
 *
 * @param goals            virtue thresholds and the titles behind them
 * @param governmentTitles what the tribunal may grant, in the order it should be listed
 */
public record Catalogue(List<Goal> goals, List<String> governmentTitles) {

    public Catalogue {
        goals = List.copyOf(goals);
        governmentTitles = List.copyOf(governmentTitles);
    }

    public static Catalogue empty() {
        return new Catalogue(List.of(), List.of());
    }
}
