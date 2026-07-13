package dev.frostguard.api.domain;

import java.time.Duration;

/**
 * One row of the wilderness March Queue panel: which slot it is, what the troops are doing, and the
 * countdown the panel shows. The countdown is {@code null} whenever the row has no timer.
 */
public record MarchSlotState(int slot, MarchSlotStatus status, Duration countdown) {

    public static MarchSlotState of(int slot, MarchSlotStatus status) {
        return new MarchSlotState(slot, status, null);
    }

    public boolean isIdle() {
        return status == MarchSlotStatus.IDLE;
    }

    /** True when the countdown marks the moment this slot frees up. */
    public boolean hasReturnCountdown() {
        return countdown != null
                && (status == MarchSlotStatus.GATHERING || status == MarchSlotStatus.RETURNING);
    }
}
