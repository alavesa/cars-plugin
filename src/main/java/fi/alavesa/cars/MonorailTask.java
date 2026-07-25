package fi.alavesa.cars;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives every monorail cart along its line, once per tick. A cart heads toward its current
 * target node; on arrival it advances to the next node, ping-ponging between the two ends of
 * the line with a short dwell (a station stop) at each end. Movement is by velocity so the
 * rider (and the body/hitbox passengers) travel with the base; the seat stand is teleported
 * each tick with RETAIN_PASSENGERS to keep the rider glued to the cart.
 */
public final class MonorailTask implements Runnable {

    private final CarsPlugin plugin;
    private final MonorailManager mono;
    private final Map<UUID, Integer> dwell = new HashMap<>();   // base UUID -> ticks left paused

    public MonorailTask(CarsPlugin plugin, MonorailManager mono) {
        this.plugin = plugin;
        this.mono = mono;
    }

    @Override
    public void run() {
        double speed = plugin.getConfig().getDouble("monorail.speed", 0.3);
        double arrive = plugin.getConfig().getDouble("monorail.arrive", 0.7);
        int dwellTicks = plugin.getConfig().getInt("monorail.dwell-ticks", 40);
        double seatY = plugin.getConfig().getDouble("monorail.seat-y", 0.4);

        for (World world : Bukkit.getWorlds()) {
            for (Pig base : world.getEntitiesByClass(Pig.class)) {
                if (!base.getScoreboardTags().contains(MonorailManager.TAG_MONO)) continue;
                tickCart(base, speed, arrive, dwellTicks, seatY);
            }
        }
    }

    private void tickCart(Pig base, double speed, double arrive, int dwellTicks, double seatY) {
        var pdc = base.getPersistentDataContainer();
        String lineName = pdc.get(mono.lineKey, PersistentDataType.STRING);
        MonorailManager.Line line = lineName == null ? null : mono.line(lineName);
        if (line == null || line.nodes.size() < 2) { base.setVelocity(new Vector(0, 0, 0)); return; }

        int idx = clamp(pdc.getOrDefault(mono.idxKey, PersistentDataType.INTEGER, 1), 0, line.nodes.size() - 1);
        int dir = pdc.getOrDefault(mono.dirKey, PersistentDataType.INTEGER, 1) >= 0 ? 1 : -1;

        Vector target = line.nodes.get(idx);
        Location here = base.getLocation();
        Vector to = target.clone().subtract(here.toVector());
        double dist = to.length();

        // Station dwell: sit still at an end for a moment before heading back.
        Integer paused = dwell.get(base.getUniqueId());
        if (paused != null && paused > 0) {
            dwell.put(base.getUniqueId(), paused - 1);
            base.setVelocity(new Vector(0, 0, 0));
            positionSeat(base, seatY);
            return;
        }

        if (dist <= arrive) {
            // Arrived at this node - pick the next one, reversing at the ends.
            int next = idx + dir;
            if (next >= line.nodes.size()) { dir = -1; next = line.nodes.size() - 2; dwell.put(base.getUniqueId(), dwellTicks); }
            else if (next < 0) { dir = 1; next = 1; dwell.put(base.getUniqueId(), dwellTicks); }
            pdc.set(mono.idxKey, PersistentDataType.INTEGER, next);
            pdc.set(mono.dirKey, PersistentDataType.INTEGER, dir);
            base.setVelocity(new Vector(0, 0, 0));
            positionSeat(base, seatY);
            return;
        }

        // Head toward the target node in full 3D (the line can climb and dip).
        Vector vel = to.normalize().multiply(Math.min(speed, dist));
        base.setVelocity(vel);

        float[] rot = MonorailManager.orient(to);
        base.setRotation(rot[0], 0);
        for (var passenger : base.getPassengers()) {
            if (passenger instanceof ItemDisplay body) body.setRotation(rot[0], rot[1]);
        }
        positionSeat(base, seatY);
    }

    /** Keep the rider's seat stand glued to the base, a little above it. */
    private void positionSeat(Pig base, double seatY) {
        String baseId = base.getUniqueId().toString();
        for (ArmorStand seat : base.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!seat.getScoreboardTags().contains(MonorailManager.TAG_MONO_SEAT)) continue;
            String owner = seat.getPersistentDataContainer().get(mono.baseKey, PersistentDataType.STRING);
            if (owner == null || !owner.equals(baseId)) continue;
            Location target = base.getLocation().clone().add(0, seatY, 0);
            target.setYaw(base.getLocation().getYaw());
            seat.teleport(target, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
            return;
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
