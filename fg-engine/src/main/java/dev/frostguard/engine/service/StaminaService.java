package dev.frostguard.engine.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.engine.listener.StaminaChangeListener;

/**
 * Tracks per-account energy levels and drives a background regeneration
 * loop that ticks once per {@link #TICK_INTERVAL}.  Thread-safe for
 * concurrent reads and writes from multiple task queues.
 *
 * <p>The singleton is accessed via {@link #getServices()}.  Observers
 * registered through {@link #addStaminaChangeListener} are notified on
 * the regen-ticker thread — UI callers must dispatch to their own thread.
 */
public final class StaminaService {

    private static final Logger log = LoggerFactory.getLogger(StaminaService.class);

    private static final Duration TICK_INTERVAL = Duration.ofMinutes(5);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(30);
    private static final int REGEN_CEILING = 200;
    private static final int MAX_STAMINA = 200;

    /** Compact snapshot of a single account's energy state. */
    private record EnergySlot(int level, Instant lastTouched) {
        EnergySlot withLevel(int newLevel) {
            return new EnergySlot(Math.max(0, newLevel), Instant.now());
        }
        EnergySlot bumped() {
            return (level < REGEN_CEILING)
                    ? new EnergySlot(level + 1, lastTouched)
                    : this;
        }
        boolean isStale() {
            return Duration.between(lastTouched, Instant.now())
                           .compareTo(STALE_THRESHOLD) >= 0;
        }
    }

    private static volatile StaminaService instance;

    private final ConcurrentHashMap<Long, EnergySlot> slots = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<StaminaChangeListener> observers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker;

    private StaminaService() {
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fg-energy-regen");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(
                this::runRegenTick,
                TICK_INTERVAL.toMinutes(),
                TICK_INTERVAL.toMinutes(),
                TimeUnit.MINUTES);
    }

    /** Returns the shared service instance, creating it on first access. */
    public static StaminaService getServices() {
        StaminaService local = instance;
        if (local == null) {
            synchronized (StaminaService.class) {
                local = instance;
                if (local == null) {
                    local = new StaminaService();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ── Observer management ──────────────────────────────────────────

    public void addStaminaChangeListener(StaminaChangeListener listener) {
        Objects.requireNonNull(listener);
        observers.addIfAbsent(listener);
    }

    public void removeStaminaChangeListener(StaminaChangeListener listener) {
        observers.remove(listener);
    }

    // ── Stamina mutations ────────────────────────────────────────────

    public void setStamina(Long profileId, int stamina) {
        requireId(profileId);
        int clamped = clampStamina(stamina);
        EnergySlot fresh = new EnergySlot(clamped, Instant.now());
        slots.put(profileId, fresh);
        broadcastChange(profileId, fresh.level());
    }

    public void addStamina(Long profileId, int amount) {
        requireId(profileId);
        int updated = applyDelta(profileId, amount);
        broadcastChange(profileId, updated);
    }

    public void subtractStamina(Long profileId, int amount) {
        requireId(profileId);
        int updated = applyDelta(profileId, -amount);
        broadcastChange(profileId, updated);
    }

    // ── Queries ──────────────────────────────────────────────────────

    public int getCurrentStamina(Long profileId) {
        requireId(profileId);
        EnergySlot slot = slots.get(profileId);
        return (slot != null) ? slot.level() : 0;
    }

    public boolean requiresUpdate(Long profileId) {
        requireId(profileId);
        EnergySlot slot = slots.get(profileId);
        return slot == null || slot.isStale();
    }

    // ── Internals ────────────────────────────────────────────────────

    private int applyDelta(Long profileId, int delta) {
        AtomicInteger result = new AtomicInteger();
        slots.compute(profileId, (id, existing) -> {
            int base = (existing != null) ? existing.level() : 0;
            int clamped = clampStamina(base + delta);
            result.set(clamped);
            return new EnergySlot(clamped, (existing != null) ? existing.lastTouched() : Instant.now());
        });
        return result.get();
    }

    private int clampStamina(int value) {
        return Math.max(0, Math.min(MAX_STAMINA, value));
    }

    private void runRegenTick() {
        try {
            AtomicInteger affected = new AtomicInteger();
            notifySweepStart();
            slots.replaceAll((id, slot) -> {
                if (slot.level() < REGEN_CEILING) {
                    EnergySlot bumped = slot.bumped();
                    if (bumped.level() != slot.level()) {
                        affected.incrementAndGet();
                        broadcastChange(id, bumped.level());
                    }
                    return bumped;
                }
                return slot;
            });
            notifySweepEnd(affected.get());
        } catch (Exception ex) {
            log.warn("Energy regeneration tick failed", ex);
        }
    }

    private void broadcastChange(Long profileId, int level) {
        for (StaminaChangeListener obs : observers) {
            try {
                obs.onEnergyLevelChanged(profileId, level);
            } catch (Exception ex) {
                log.warn("Observer error during energy broadcast", ex);
            }
        }
    }

    private void notifySweepStart() {
        for (StaminaChangeListener obs : observers) {
            try { obs.onRegenerationSweepStarting(); }
            catch (Exception ignored) { /* non-critical */ }
        }
    }

    private void notifySweepEnd(int count) {
        for (StaminaChangeListener obs : observers) {
            try { obs.onRegenerationSweepFinished(count); }
            catch (Exception ignored) { /* non-critical */ }
        }
    }

    private static void requireId(Long id) {
        if (id == null) throw new IllegalArgumentException("Account identifier must not be null");
    }
}
