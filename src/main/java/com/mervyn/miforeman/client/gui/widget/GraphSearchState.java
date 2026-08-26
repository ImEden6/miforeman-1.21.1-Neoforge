package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * State management and matching logic for searching nodes inside the DAG canvas.
 */
public class GraphSearchState {
    private String query = "";
    private List<ResourceLocation> matches = Collections.emptyList();
    private Set<ResourceLocation> matchSet = Collections.emptySet();
    private int currentIndex = -1;

    public void setQuery(String newQuery, Collection<RecipeGraphNode> nodes) {
        this.query = newQuery == null ? "" : newQuery.trim();
        if (this.query.isEmpty() || nodes.isEmpty()) {
            this.matches = Collections.emptyList();
            this.matchSet = Collections.emptySet();
            this.currentIndex = -1;
            return;
        }

        String queryLower = this.query.toLowerCase(Locale.ROOT);
        ResourceLocation previousSelected = currentMatchId();

        List<ResourceLocation> matchedList = new ArrayList<>();
        Set<ResourceLocation> matchedSet = new HashSet<>();

        for (RecipeGraphNode node : nodes) {
            ResourceLocation id = node.getId();
            String rawId = id.toString().toLowerCase(Locale.ROOT);
            String path = id.getPath().toLowerCase(Locale.ROOT);
            String formattedName = DisplayFormat.formatId(id).toLowerCase(Locale.ROOT);

            if (rawId.contains(queryLower) || path.contains(queryLower) || formattedName.contains(queryLower)) {
                if (matchedSet.add(id)) {
                    matchedList.add(id);
                }
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

    public List<ResourceLocation> getMatches() {
        return matches;
    }

    public int getMatchCount() {
        return matches.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public @Nullable ResourceLocation currentMatchId() {
        if (currentIndex >= 0 && currentIndex < matches.size()) {
            return matches.get(currentIndex);
        }
        return null;
    }

    public @Nullable ResourceLocation nextMatch() {
        if (matches.isEmpty()) {
            currentIndex = -1;
            return null;
        }
        currentIndex = (currentIndex + 1) % matches.size();
        return matches.get(currentIndex);
    }

    public @Nullable ResourceLocation prevMatch() {
        if (matches.isEmpty()) {
            currentIndex = -1;
            return null;
        }
        currentIndex = (currentIndex - 1 + matches.size()) % matches.size();
        return matches.get(currentIndex);
    }

    public boolean isMatch(ResourceLocation id) {
        return matchSet.contains(id);
    }

    public boolean isCurrentMatch(ResourceLocation id) {
        ResourceLocation current = currentMatchId();
        return current != null && current.equals(id);
    }

    public void clear() {
        this.query = "";
        this.matches = Collections.emptyList();
        this.matchSet = Collections.emptySet();
        this.currentIndex = -1;
    }
}
