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
    private Status status = Status.PREPARING;
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
    void beginReset() { status = Status.RESETTING; }
    void restored() { status = reservation == null ? Status.READY : Status.IN_USE; }
    void failed() { status = Status.FAILED; }
    public void release(ArenaLease lease) {
        if (!owns(lease)) return;
        reservation = null;
        if (status == Status.IN_USE) status = Status.READY;
    }
}
