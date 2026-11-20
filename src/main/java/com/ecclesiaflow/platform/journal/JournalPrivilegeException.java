package com.ecclesiaflow.platform.journal;

import java.util.List;

/**
 * The module's database role could rewrite its own journal, or cannot write it. Thrown at startup: a journal its
 * writer may edit is a note, not evidence.
 */
public class JournalPrivilegeException extends IllegalStateException {

    private final List<String> problems;

    public JournalPrivilegeException(String role, List<String> problems) {
        super("platform journal: the database role " + role + " must hold INSERT and SELECT on the lines and EXECUTE "
                + "on the head's reader and the purge, nothing more:\n - " + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
