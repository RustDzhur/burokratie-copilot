package de.buerokratiecopilot.documents.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed transitions of a voucher in the office queue (§8.1).
 *
 * <p>Deliberately narrow. A voucher moves forward one step at a time, a failure can strike any step, and a
 * failed voucher can be retried. Nothing jumps from {@code NEU} straight to {@code EXPORTIERT}: a batch that
 * takes vouchers nobody has looked at is a batch the office cannot stand behind.</p>
 */
public final class BelegStatusMachine {

    private static final Map<BelegStatus, Set<BelegStatus>> ALLOWED = new EnumMap<>(BelegStatus.class);

    static {
        allow(BelegStatus.NEU, BelegStatus.OFFEN, BelegStatus.FEHLER);
        allow(BelegStatus.OFFEN, BelegStatus.ZU_PRUEFEN, BelegStatus.FEHLER);
        allow(BelegStatus.ZU_PRUEFEN, BelegStatus.OFFEN, BelegStatus.EXPORTIERT, BelegStatus.FEHLER);
        allow(BelegStatus.EXPORTIERT, BelegStatus.FEHLER);
        // A retry is a new attempt at the same work, so it starts again at the beginning.
        allow(BelegStatus.FEHLER, BelegStatus.NEU);
    }

    private BelegStatusMachine() {
    }

    private static void allow(BelegStatus from, BelegStatus... to) {
        ALLOWED.put(from, EnumSet.of(to[0], to));
    }

    /**
     * Whether a voucher may move from one status to another.
     *
     * <p>Staying where it is counts as allowed: a repeated confirmation must not be an error, because the
     * person clicking twice is not making a mistake.</p>
     */
    public static boolean canMove(BelegStatus from, BelegStatus to) {
        if (from == to) {
            return true;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws when the move is not allowed, naming both ends so the message is usable in a log. */
    public static void requireMove(BelegStatus from, BelegStatus to) {
        if (!canMove(from, to)) {
            throw new IllegalStateException("Ein Beleg kann nicht von " + from + " nach " + to + " wechseln.");
        }
    }

    /** True when the voucher still needs somebody: the states that belong in the morning queue. */
    public static boolean isOpen(BelegStatus status) {
        return status == BelegStatus.NEU || status == BelegStatus.OFFEN || status == BelegStatus.ZU_PRUEFEN;
    }
}
