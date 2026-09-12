package me.advait.contender.arena;

import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.BlockBounds;
import java.util.UUID;

/** A prebuilt copy. Reservations and status changes run on the server thread. */
public final class ArenaInstance {
    public enum Status { PREPARING, READY, IN_USE, RESETTING, FAILED }
    private final int slot;
    private final ArenaMap map;
    private final BlockBounds cell;
    private volatile Status status = Status.PREPARING;
    private volatile long resetRevision;
    private UUID reservation;

    public ArenaInstance(int slot, ArenaMap map, BlockBounds cell) {
        this.slot = slot;
        this.map = map;
        this.cell = cell;
    }
    public int slot() { return slot; }
    public ArenaMap map() { return map; }
    public BlockBounds cell() { return cell; }
    public Status status() { return status; }
    public boolean isReserved() { return reservation != null; }
    public ArenaLease acquire() {
        if (status != Status.READY || reservation != null) return null;
        reservation = UUID.randomUUID();
        status = Status.IN_USE;
        return new ArenaLease(this, reservation);
    }
    public boolean owns(ArenaLease lease) {
        return lease != null && lease.instance() == this && lease.token().equals(reservation);
    }
    long beginReset() {
        if (status == Status.FAILED) throw new IllegalStateException("Rebuild this arena copy before using it again.");
        long revision = ++resetRevision;
        status = Status.RESETTING;
        return revision;
    }
    boolean isReset(long revision) { return resetRevision == revision && status == Status.RESETTING; }
    boolean restored(long revision) {
        if (!isReset(revision)) return false;
        status = reservation == null ? Status.READY : Status.IN_USE;
        return true;
    }
    void failed(long revision) { if (isReset(revision)) failed(); }
    void failed() {
        status = Status.FAILED;
        resetRevision++;
    }
    public void release(ArenaLease lease) {
        if (!owns(lease)) return;
        reservation = null;
        if (status == Status.IN_USE) status = Status.READY;
    }
}
