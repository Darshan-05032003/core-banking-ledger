package com.darshan.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

// Stands still like Clock.fixed until a test moves it, which is how a test posts entries "later"
// without waiting. A test that moves it must call reset() afterwards: the context is shared.
public final class MutableClock extends Clock {

    private final Instant origin;
    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public MutableClock(Instant origin, ZoneId zone) {
        this.origin = origin;
        this.zone = zone;
        this.instant = new AtomicReference<>(origin);
    }

    public void advance(Duration duration) {
        instant.updateAndGet(current -> current.plus(duration));
    }

    public void reset() {
        instant.set(origin);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        MutableClock copy = new MutableClock(origin, newZone);
        copy.instant.set(instant.get());
        return copy;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
