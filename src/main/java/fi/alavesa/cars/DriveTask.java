package fi.alavesa.cars;

import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The engine. Every tick, each car (an invisible pig carrying a mount hitbox
 * and the driver) reads its driver's live WASD input (PlayerInputEvent) and
 * turns it into velocity: W throttles, S brakes then reverses, A/D steer -
 * sharper at speed, reversed in reverse, like a car. The visible body is this
 * car's own BetterModel model instance, rendered on the base Pig and
 * auto-followed by BetterModel (position + the body yaw we set each tick), so
 * nothing is teleported for it. Seats are free-standing invisible armor stands
 * teleported along, keeping their riders aboard.
 */
public final class DriveTask implements Runnable {

    public static final String TAG_CAR = "cars.car";
    public static final String TAG_PART = "cars.part";
    public static final String TAG_SEAT = "cars.seat";

    // ------------------------------------------------------------- drift knobs
    /** Above this speed (blocks/s) a sharp turn breaks traction into a slide. */
    private static final double DRIFT_SPEED = 4.0;
    /** How much the pointing yaw must lead the velocity heading (degrees) for
     *  the slide to kick in - below this it's just normal cornering grip. */
    private static final double DRIFT_ANGLE = 14.0;
    /** Per-tick fraction the velocity heading chases the pointing yaw while
     *  drifting: low = long lazy slides, high = the tail snaps back fast. */
    private static final double DRIFT_GRIP = 0.12;
    /** Per-tick catch-up when NOT drifting - grip is basically total, so
     *  straight-line driving points exactly where the nose points. */
    private static final double GRIP_NORMAL = 0.9;
    /** While drifting, the surface grip is scaled by this so the car keeps its
     *  momentum and actually SLIDES (even on high-grip pavement) instead of
     *  snapping to the nose. Lower = looser, longer slides. */
    private static final double DRIFT_TRACTION = 0.45;
    /** Sneak only EXITS the car below this speed (blocks/s); at or above it, sneak
     *  is the drift handbrake and does not dismount the driver (see CarListener). */
    public static final double HANDBRAKE_MIN_SPEED = 3.0;

    /** Fallback seat offsets [x, y, z] when a type has none configured.
     *  Index 0 is the driver. Axes: +Z forward, +X across, +Y up (blocks). */
    private static final double[][] DEFAULT_SEATS = {
        {0.35, 1.0, 0.3}, {-0.35, 1.0, 0.3}, {0.35, 1.0, -0.6}, {-0.35, 1.0, -0.6}};

    private final CarsPlugin plugin;
    private final Map<UUID, Double> speeds = new HashMap<>();
    private final Map<UUID, Input> inputs = new HashMap<>();
    private final Set<UUID> prepared = new HashSet<>();
    private final Map<UUID, Vector> momentum = new HashMap<>();
    private final Map<UUID, Float> yaws = new HashMap<>();
    /** The heading (degrees) our velocity actually travels along - it lags
     *  the pointing yaw during a drift, then grips back onto it. */
    private final Map<UUID, Float> velHeading = new HashMap<>();

    /** How the ground drives back: [speed factor, grip]. */
    private static double[] surface(Block ground, boolean inWater) {
        if (inWater) return new double[]{0.35, 0.5};
        Material m = ground.getType();
        String name = m.name();
        if (name.contains("ICE")) return new double[]{1.0, 0.22};              // skating rink
        if (name.contains("SAND") || m == Material.GRAVEL || m == Material.MUD
            || m == Material.SOUL_SAND || m == Material.SOUL_SOIL) return new double[]{0.55, 0.8};
        if (name.contains("SNOW")) return new double[]{0.7, 0.75};
        if (name.contains("DIRT") || name.contains("GRASS") || m == Material.PODZOL
            || m == Material.MYCELIUM || m == Material.DIRT_PATH
            || name.contains("MOSS")) return new double[]{0.8, 0.95};
        return new double[]{1.0, 1.0};                                          // pavement
    }
    private int tick;

    private WinchManager winch;

    public DriveTask(CarsPlugin plugin) {
        this.plugin = plugin;
    }

    public void setWinch(WinchManager winch) { this.winch = winch; }

    public void input(Player player, Input input) {
        inputs.put(player.getUniqueId(), input);
    }

    public void forget(UUID player) {
        inputs.remove(player);
    }

    /** Current speed (blocks/s, unsigned) of the car with this pig UUID - used by
     *  the dismount guard so sneak is a handbrake at speed, an exit when slow. */
    public double carSpeed(UUID carId) {
        return Math.abs(speeds.getOrDefault(carId, 0.0));
    }

    @Override
    public void run() {
        tick++;
        for (World world : Bukkit.getWorlds()) {
            for (Pig pig : world.getEntitiesByClass(Pig.class)) {
                if (pig.getScoreboardTags().contains(TAG_CAR)) {
                    tickCar(pig);
                    pollPunch(pig);
                    if (tick % 20 == 0) forkliftPickup(pig);   // ~1s scan for barrels to auto-load
                }
            }
        }
        if (tick % 100 == 0) sweepOrphans();
    }

    /** A FORKLIFT auto-reels a barrel it drives up to (no winch needed) onto its own bed. */
    private void forkliftPickup(Pig base) {
        if (winch == null || plugin.isWrecked(base)) return;
        CarType type = plugin.typeOf(base);
        if (type == null || !type.forklift) return;
        Location c = base.getLocation();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
            org.bukkit.block.Block b = c.clone().add(dx, dy, dz).getBlock();
            if (b.getType() == org.bukkit.Material.BARREL) { winch.reelBlockToCar(b, base); return; }  // one per scan
        }
    }

    /** Detect a left-click PUNCH on the car's hitbox (Interaction entities don't fire damage events, so we
     *  poll their last-attack timestamp) and turn it into car damage. Gun SHOTS come through the damage
     *  event in CarListener instead. */
    private static final double PUNCH_DAMAGE = 4.0;
    private void pollPunch(Pig base) {
        org.bukkit.entity.Interaction hitbox = plugin.hitboxOf(base);
        if (hitbox == null) return;
        var attack = hitbox.getLastAttack();
        if (attack == null) return;
        long ts = attack.getTimestamp();
        var pdc = hitbox.getPersistentDataContainer();
        long seen = pdc.getOrDefault(plugin.attackKey(),
            org.bukkit.persistence.PersistentDataType.LONG, 0L);
        if (ts == seen) return;                       // already counted this punch
        pdc.set(plugin.attackKey(), org.bukkit.persistence.PersistentDataType.LONG, ts);
        if (seen == 0L) return;                        // first observation after (re)load: don't retro-punch
        org.bukkit.entity.Player puncher = attack.getPlayer().isOnline()
            ? Bukkit.getPlayer(attack.getPlayer().getUniqueId()) : null;
        plugin.damageCar(base, PUNCH_DAMAGE, puncher);
    }

    private void tickCar(Pig base) {
        if (prepared.add(base.getUniqueId())) {
            // repair pass: velocity needs AI on, decision-making goes off for
            // good (aware=false persists), and stray goals are stripped
            base.setAI(true);
            base.setAware(false);
            Bukkit.getMobGoals().removeAllGoals(base);
            // v0.2.x cars have no driver's seat (index 0) - retrofit one
            boolean hasDriverSeat = collectSeats(base).stream().anyMatch(s ->
                s.getPersistentDataContainer().getOrDefault(plugin.seatKey(),
                    PersistentDataType.INTEGER, -1) == 0);
            if (!hasDriverSeat) plugin.spawnSeat(base, 0);
        }
        CarType type = plugin.registry().get(
            base.getPersistentDataContainer().getOrDefault(plugin.typeKey(), PersistentDataType.STRING, ""));
        if (type == null) return;

        List<ArmorStand> seats = collectSeats(base);
        Player driver = seats.isEmpty() ? null : seats.get(0).getPassengers().stream()
            .filter(e -> e instanceof Player).map(e -> (Player) e)
            .findFirst().orElse(null);
        if (driver == null) {
            // legacy cars (v0.2.x): the driver used to ride the pig itself
            driver = base.getPassengers().stream()
                .filter(e -> e instanceof Player).map(e -> (Player) e)
                .findFirst().orElse(null);
        }
        double speed = speeds.getOrDefault(base.getUniqueId(), 0.0);
        // OUR yaw is the steering state. The entity's own yaw is never read
        // back: vanilla rotates a ridden mob's body toward its velocity, and
        // reading that back fed our steering into itself - the death spin.
        float yaw = yaws.computeIfAbsent(base.getUniqueId(),
            id -> base.getLocation().getYaw());

        boolean handbrake = false;   // sneak = handbrake: intentional drifting
        if (driver != null) {
            Input input = inputs.get(driver.getUniqueId());
            handbrake = input != null && input.isSneak() && Math.abs(speed) > 0.4;
            double perTickAccel = type.acceleration / 20.0;
            if (input != null) {
                if (input.isForward()) speed += perTickAccel;
                else if (input.isBackward()) speed -= perTickAccel * (speed > 0.1 ? 2.0 : 1.0);
                else speed *= 0.97;
                double steer = (input.isLeft() ? -1 : 0) + (input.isRight() ? 1 : 0);
                if (steer != 0 && Math.abs(speed) > 0.4) {
                    double grip = Math.min(1.0, Math.abs(speed) / (type.maxSpeed * 0.35));
                    yaw += (float) (steer * type.turnRate * grip * Math.signum(speed));
                }
            } else {
                speed *= 0.97;
            }
            speed = Math.max(-type.maxSpeed * 0.35, Math.min(type.maxSpeed, speed));
        } else {
            speed *= 0.85; // handbrake creeps on when nobody is driving
        }
        if (Math.abs(speed) < 0.05) speed = 0;

        // ---- the ground talks back: speed cap, grip, drift ----
        Location at = base.getLocation();
        Block ground = at.clone().subtract(0, 0.2, 0).getBlock();
        if (ground.isPassable()) ground = ground.getRelative(org.bukkit.block.BlockFace.DOWN);
        double[] surf = surface(ground, base.isInWater());
        double speedFactor = surf[0], grip = surf[1];
        speed = Math.max(-type.maxSpeed * 0.35 * speedFactor,
            Math.min(type.maxSpeed * speedFactor, speed));
        speeds.put(base.getUniqueId(), speed);

        double radians = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(radians), 0, Math.cos(radians));

        // ---- drift: the velocity heading lags the nose, then grips back ----
        // velHeading is where we're actually sliding. When the nose swings
        // ahead of it faster than grip can follow (sharp turn at speed), the
        // gap opens and the car slides sideways; grip then reels it back in.
        float vh = velHeading.getOrDefault(base.getUniqueId(), yaw);
        float gap = wrapDegrees(yaw - vh);
        // Drift when the handbrake (sneak) is held at speed, or a turn is sharp
        // enough that grip can't hold the tail. Handbrake is the reliable,
        // discoverable trigger; the sharp-turn path lets it happen naturally too.
        boolean sharpDrift = Math.abs(speed) > DRIFT_SPEED && Math.abs(gap) > DRIFT_ANGLE;
        boolean drifting = type.drift && Math.abs(speed) > DRIFT_SPEED && (handbrake || sharpDrift);
        // reversing points the slide the other way so the tail behaves
        double catchUp = drifting ? DRIFT_GRIP : GRIP_NORMAL;
        vh += (float) (gap * catchUp);
        velHeading.put(base.getUniqueId(), vh);

        double vhRad = Math.toRadians(vh);
        Vector slideDir = new Vector(-Math.sin(vhRad), 0, Math.cos(vhRad));
        Vector desired = slideDir.multiply(speed / 20.0);
        // low grip = momentum wins over steering: hello, ice
        Vector kept = momentum.getOrDefault(base.getUniqueId(), desired.clone());
        // While drifting we deliberately lose traction so momentum (the old
        // velocity) carries the car sideways instead of instantly following the
        // nose - that's the actual slide, felt even on full-grip pavement.
        double effGrip = drifting ? grip * DRIFT_TRACTION : grip;
        Vector velocity = kept.multiply(1.0 - effGrip).add(desired.multiply(effGrip));
        momentum.put(base.getUniqueId(), velocity.clone());
        velocity.setY(Math.min(0.1, base.getVelocity().getY())); // gravity keeps working
        base.setVelocity(velocity);
        base.setRotation(yaw, 0);
        yaws.put(base.getUniqueId(), yaw);

        // The visible body is this car's BetterModel model, attached to the base in CarsPlugin.spawnCar and
        // auto-followed by BetterModel from the base's position + the body yaw set just above - no teleport here.

        if (Math.abs(speed) > 0.4 && tick % 6 == 0) {
            float pitch = (float) (0.6 + Math.abs(speed) / type.maxSpeed);
            base.getWorld().playSound(at, type.sound, 0.7f, pitch);
        }
        // the wheels kick up whatever they are driving on
        if (Math.abs(speed) > 2.0 && tick % 3 == 0 && !ground.isPassable()) {
            base.getWorld().spawnParticle(Particle.BLOCK,
                at.clone().add(forward.clone().multiply(-0.9)).add(0, 0.15, 0),
                4, 0.3, 0.05, 0.3, ground.getBlockData());
        }
        if (base.isInWater() && tick % 10 == 0 && Math.abs(speed) > 0.3) {
            base.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.3f);
            base.getWorld().spawnParticle(Particle.SPLASH, at, 12, 0.5, 0.2, 0.5, 0);
        }
        // cosy campfire smoke curling off ALL FOUR wheels while drifting
        if (drifting && tick % 2 == 0) {
            Vector fwd = forward.clone();                               // nose direction
            Vector side = new Vector(Math.cos(radians), 0, Math.sin(radians)); // right
            Location center = at.clone().add(0, 0.1, 0);
            double halfLen = 0.9, halfWid = 0.5;                        // wheelbase / track
            for (int f = -1; f <= 1; f += 2) {                         // front / rear
                for (int s = -1; s <= 1; s += 2) {                     // left / right
                    Location wheel = center.clone()
                        .add(fwd.clone().multiply(halfLen * f))
                        .add(side.clone().multiply(halfWid * s));
                    base.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                        wheel, 2, 0.08, 0.02, 0.08, 0.005);
                }
            }
        }
        // speedometer on the driver's actionbar - top line, above everything
        if (driver != null) showSpeedometer(driver, Math.abs(speed));
        positionSeats(base, type, seats, yaw);
    }

    /** Shortest signed distance between two yaws, in [-180, 180). */
    private static float wrapDegrees(double degrees) {
        double d = ((degrees + 180) % 360 + 360) % 360 - 180;
        return (float) d;
    }

    /** "⏲ 14.2 blocks/s" on the driver's actionbar, green->yellow->red as it
     *  climbs. Routed through {@link Msg#speedometer} so, when Labra is on the
     *  server, it rides the ActionBars hub's TOP slot and reads out ABOVE the
     *  NVG battery bar and any other indicator. */
    private void showSpeedometer(Player driver, double blocksPerSecond) {
        // `speed` already IS blocks/second: the per-tick velocity is speed/20,
        // so over 20 ticks the car covers `speed` blocks. (The old code did a
        // second x20 here and read ~180 for a ~9 blocks/s car.)
        double bps = blocksPerSecond;
        // colour ramps with speed: 0 -> green, ~14+ -> red
        float hue = (float) (0.33 - 0.33 * Math.min(1.0, bps / 14.0)); // 0.33=green,0=red
        net.kyori.adventure.text.format.TextColor color =
            net.kyori.adventure.text.format.TextColor.color(
                java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f));
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component
            .text(String.format(java.util.Locale.ROOT, "⏲ %.1f blocks/s", bps))
            .color(color);
        Msg.speedometer(driver, line);
    }

    /** All seat stands of this car, sorted by seat index (0 = driver). */
    public List<ArmorStand> collectSeats(Pig base) {
        List<ArmorStand> seats = new java.util.ArrayList<>();
        for (ArmorStand stand : base.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!stand.getScoreboardTags().contains(TAG_SEAT)) continue;
            String carId = stand.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
            if (carId != null && carId.equals(base.getUniqueId().toString())) seats.add(stand);
        }
        seats.sort(java.util.Comparator.comparingInt(s ->
            s.getPersistentDataContainer().getOrDefault(plugin.seatKey(), PersistentDataType.INTEGER, 0)));
        return seats;
    }

    /** Seat position in model space [x, y, z] for a seat index. */
    private double[] seatOffset(CarType type, int index) {
        if (index < type.seatOffsets.size()) return type.seatOffsets.get(index);
        return DEFAULT_SEATS[Math.min(index, DEFAULT_SEATS.length - 1)];
    }

    /** Seats are placed at the offsets set with /car seat (in blocks, relative to the car base). Rider
     *  height fine-tuning: global seat-y-adjust in config.yml, per-type via /car edit - the per-type value
     *  applies live, no respawn needed. */
    private void positionSeats(Pig base, CarType type, List<ArmorStand> seats, float yaw) {
        double radians = Math.toRadians(yaw);
        // +Z -> forward, +X across, consistent with the model's facing
        Vector axisZ = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        Vector axisX = new Vector(Math.cos(radians), 0, Math.sin(radians));
        double riderOffset = plugin.getConfig().getDouble("seat-y-adjust", -0.72);
        double baseY = base.getLocation().getY();
        for (ArmorStand seat : seats) {
            int index = seat.getPersistentDataContainer().getOrDefault(plugin.seatKey(), PersistentDataType.INTEGER, 0);
            double[] off = seatOffset(type, index);
            double seatY = baseY + off[1] + riderOffset + type.seatYAdjust;
            Location target = base.getLocation().clone()
                .add(axisX.clone().multiply(off[0]))
                .add(axisZ.clone().multiply(off[2]));
            target.setY(seatY);
            // Armor stands ignore velocity entirely (the "seat stayed at the
            // spawn point" bug) - teleport them instead, keeping the rider
            // aboard with Paper's RETAIN_PASSENGERS flag.
            target.setYaw(yaw);
            seat.teleport(target, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    private void sweepOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand seat : world.getEntitiesByClass(ArmorStand.class)) {
                if (!seat.getScoreboardTags().contains(TAG_SEAT)) continue;
                String carId = seat.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
                if (carId == null || !(Bukkit.getEntity(UUID.fromString(carId)) instanceof Pig)) {
                    seat.remove();
                }
            }
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(TAG_PART) && entity.getVehicle() == null) {
                    entity.remove();
                }
            }
        }
    }
}
