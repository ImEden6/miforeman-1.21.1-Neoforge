package com.mervyn.miforeman.client.gui.widget;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Domain-agnostic search/match/cycle state, generic over whatever ID type a caller wants to
 * search over. Originally hardwired to {@code RecipeGraphNode}/{@code ResourceLocation} for the
 * recipe graph's search bar; generalized so {@code ReviewMachinesScreen}/{@code MonitoringScreen}
 * can reuse the same matching/cycling logic against their own row types without duplicating it.
 * <p>Callers supply pre-extracted searchable text per ID (e.g. a graph node's raw id, path, and
 * formatted name; or a row's machine name, product label, and end-product names). This class has
 * no knowledge of what those strings mean, only that a query is matched against them.
 */
public class SearchState<ID> {
    private String query = "";
    private List<ID> matches = Collections.emptyList();
    private Set<ID> matchSet = Collections.emptySet();
    private int currentIndex = -1;

    public void setQuery(String newQuery, Map<ID, List<String>> searchableTextsById) {
        this.query = newQuery == null ? "" : newQuery.trim();
        if (this.query.isEmpty() || searchableTextsById.isEmpty()) {
            this.matches = Collections.emptyList();
            this.matchSet = Collections.emptySet();
            this.currentIndex = -1;
            return;
        }

        String queryLower = this.query.toLowerCase(Locale.ROOT);
        ID previousSelected = currentMatchId();

        List<ID> matchedList = new ArrayList<>();
        Set<ID> matchedSet = new HashSet<>();

        for (var entry : searchableTextsById.entrySet()) {
            ID id = entry.getKey();
            boolean matchesQuery = false;
            for (String text : entry.getValue()) {
                if (text != null && text.toLowerCase(Locale.ROOT).contains(queryLower)) {
                    matchesQuery = true;
                    break;
                }
            }
            if (matchesQuery && matchedSet.add(id)) {
                matchedList.add(id);
            }
        }

        this.matches = Collections.unmodifiableList(matchedList);
        this.matchSet = Collections.unmodifiableSet(matchedSet);

        if (this.matches.isEmpty()) {
            this.currentIndex = -1;
        } else {
            int foundIdx = previousSelected != null ? this.matches.indexOf(previousSelected) : -1;
            this.currentIndex = foundIdx >= 0 ? foundIdx : 0;
        }
    }

    public String getQuery() {
        return query;
    }

    public boolean isSearching() {
        return !query.isEmpty();
    }

    public List<ID> getMatches() {
        return matches;
    }

    public int getMatchCount() {
        return matches.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public @Nullable ID currentMatchId() {
        if (currentIndex >= 0 && currentIndex < matches.size()) {
            return matches.get(currentIndex);
        }
        return null;
    }

    public @Nullable ID nextMatch() {
        if (matches.isEmpty()) {
            currentIndex = -1;
            return null;
        }
        currentIndex = (currentIndex + 1) % matches.size();
        return matches.get(currentIndex);
    }

    public @Nullable ID prevMatch() {
        if (matches.isEmpty()) {
            currentIndex = -1;
            return null;
        }
        currentIndex = (currentIndex - 1 + matches.size()) % matches.size();
        return matches.get(currentIndex);
    }

    public boolean isMatch(ID id) {
        return matchSet.contains(id);
    }

    public boolean isCurrentMatch(ID id) {
        ID current = currentMatchId();
        return current != null && current.equals(id);
    }

    public void clear() {
        this.query = "";
        this.matches = Collections.emptyList();
        this.matchSet = Collections.emptySet();
        this.currentIndex = -1;
    }
}
