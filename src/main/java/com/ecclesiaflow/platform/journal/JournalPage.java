package com.ecclesiaflow.platform.journal;

import java.util.List;

/** @param nextCursor {@code null} on the last page */
public record JournalPage(List<JournalLine> items, String nextCursor) {

    public JournalPage {
        items = List.copyOf(items);
    }
}
