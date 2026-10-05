package com.seatreserve.service;

/** Checks for free-text input that Postgres cannot store or that does not belong in a name, id or key. */
public final class Inputs {
    private Inputs() {
    }

    /**
     * True if the text contains any control character (NUL, newline, tab...). NUL in particular cannot be
     * stored in a Postgres text column, so it used to surface as a 500 deep inside a query.
     */
    public static boolean hasControlChars(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }
}
