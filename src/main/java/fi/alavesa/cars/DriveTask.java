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
import org.bukkit.entity.ItemDisplay;
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
 * The engine. Every tick, each car (an invisible pig carrying the model
 * display, a mount hitbox and the driver) reads its driver's live WASD input
 * (PlayerInputEvent) and turns it into velocity: W throttles, S brakes then
 * reverses, A/D steer - sharper at speed, reversed in reverse, like a car.
 * Passenger seats are free-standing invisible armor stands pulled along by
 * velocity (never teleported - teleports would eject the passengers).
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
    /** How fast the drift state fades once the slide angle drops back down. */
    private static final double DRIFT_DECAY = 0.85;

    // --------------------------------------------------------- camera sway knobs
    /** Idle engine shake amplitude (degrees of roll) when barely moving. */
    private static final float SWAY_IDLE = 0.6f;
    /** Extra roll amplitude (degrees) added at full speed - the fast bounce. */
    private static final float SWAY_SPEED = 3.5f;
    /** Vertical bob amplitude (blocks) added at full speed. */
    private static final float BOB_SPEED = 0.05f;
    /** How hard the model leans into a drift (degrees of roll per unit slide). */
    private static final float LEAN_PER_DRIFT = 0.5f;
    /** Sway/bob oscillation speed - radians of phase advanced per tick. */
    private static final double SWAY_RATE = 0.55;

    /** Fallback seat offsets [x, y, z] when the model file names none.
     *  Index 0 is the driver. Model space axes: +Z forward, +X across. */
    private static final double[][] DEFAULT_SEATS = {
        {0.35, 0.35, 0.1}, {-0.35, 0.35, 0.1}, {0.35, 0.35, -0.7}, {-0.35, 0.35, -0.7}};

    private final CarsPlugin plugin;
    private final Map<UUID, Double> speeds = new HashMap<>();
    private final Map<UUID, Input> inputs = new HashMap<>();
    private final Set<UUID> prepared = new HashSet<>();
    private final Map<UUID, Vector> momentum = new HashMap<>();
    private final Map<UUID, Float> yaws = new HashMap<>();
    private final Map<UUID, Double> lastY = new HashMap<>();
    private final Map<UUID, Float> tilt = new HashMap<>();
    /** The heading (degrees) our velocity actually travels along - it lags
     *  the pointing yaw during a drift, then grips back onto it. */
    private final Map<UUID, Float> velHeading = new HashMap<>();
    /** Smoothed drift amount (signed slide angle) driving the lean + smoke. */
    private final Map<UUID, Float> drift = new HashMap<>();
    /** Smoothed model roll so the sway eases in and out (no snap). */
    private final Map<UUID, Float> roll = new HashMap<>();

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

    public DriveTask(CarsPlugin plugin) {
        this.plugin = plugin;
    }

    public void input(Player player, Input input) {
        inputs.put(player.getUniqueId(), input);
    }

    public void forget(UUID player) {
        inputs.remove(player);
    }

    @Override
    public void run() {
        tick++;
        for (World world : Bukkit.getWorlds()) {
            for (Pig pig : world.getEntitiesByClass(Pig.class)) {
                if (pig.getScoreboardTags().contains(TAG_CAR)) tickCar(pig);
            }
        }
        if (tick % 100 == 0) sweepOrphans();
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
            // repair pass for older cars: drop the glow-in-the-dark brightness
            // override so the body reacts to area lighting, and add the shadow
            for (Entity passenger : base.getPassengers()) {
                if (passenger instanceof ItemDisplay display) {
                    display.setBrightness(null);
                    display.setShadowRadius(1.15f);
                    display.setShadowStrength(0.9f);
                }
            }
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
        boolean drifting = Math.abs(speed) > DRIFT_SPEED && (handbrake || sharpDrift);
        // reversing points the slide the other way so the tail behaves
        double catchUp = drifting ? DRIFT_GRIP : GRIP_NORMAL;
        vh += (float) (gap * catchUp);
        velHeading.put(base.getUniqueId(), vh);
        // smoothed, signed slide angle for lean + smoke feedback
        float driftAmount = drift.getOrDefault(base.getUniqueId(), 0f);
        driftAmount = (float) (driftAmount * DRIFT_DECAY
            + (drifting ? gap : 0f) * (1 - DRIFT_DECAY));
        drift.put(base.getUniqueId(), driftAmount);

        double vhRad = Math.toRadians(vh);
        Vector slideDir = new Vector(-Math.sin(vhRad), 0, Math.cos(vhRad));
        Vector desired = slideDir.multiply(speed / 20.0);
        // low grip = momentum wins over steering: hello, ice
        Vector kept = momentum.getOrDefault(base.getUniqueId(), desired.clone());
        Vector velocity = kept.multiply(1.0 - grip).add(desired.multiply(grip));
        momentum.put(base.getUniqueId(), velocity.clone());
        velocity.setY(Math.min(0.1, base.getVelocity().getY())); // gravity keeps working
        base.setVelocity(velocity);
        base.setRotation(yaw, 0);
        yaws.put(base.getUniqueId(), yaw);

        // nose up the stairs, nose down the slope (visual tilt on the model)
        double dy = at.getY() - lastY.getOrDefault(base.getUniqueId(), at.getY());
        lastY.put(base.getUniqueId(), at.getY());
        double horizontal = Math.max(0.03, Math.abs(speed) / 20.0);
        float targetTilt = (float) Math.max(-25, Math.min(25,
            -Math.toDegrees(Math.atan2(dy, horizontal))));
        float smoothTilt = tilt.getOrDefault(base.getUniqueId(), 0f);
        smoothTilt += (targetTilt - smoothTilt) * 0.25f;
        if (Math.abs(speed) < 0.3) smoothTilt *= 0.8f;
        tilt.put(base.getUniqueId(), smoothTilt);

        // ---- camera sway: Paper can't move the real camera, so we shake the
        // MODEL. A sine-driven roll and a cosine-driven vertical bob, both
        // scaling from a gentle idle shudder to a real bounce at speed, plus
        // a lean INTO the current drift. Roll + bob live on the display's
        // transform (visual only - the pig physics never sees them).
        double speedFrac = Math.min(1.0, Math.abs(speed) / Math.max(0.1, type.maxSpeed));
        double phase = tick * SWAY_RATE;
        // The idle engine-shudder only runs while someone is aboard. An empty
        // car that has rolled to a stop rests flat - no perpetual wiggle.
        float idleAmp = driver != null ? SWAY_IDLE : 0f;
        float swayAmp = idleAmp + SWAY_SPEED * (float) speedFrac;
        float targetRoll = swayAmp * (float) Math.sin(phase)
            + driftAmount * LEAN_PER_DRIFT;      // lean the body into the slide
        float smoothRoll = roll.getOrDefault(base.getUniqueId(), 0f);
        smoothRoll += (targetRoll - smoothRoll) * 0.3f; // ease, never teleport
        roll.put(base.getUniqueId(), smoothRoll);
        float bob = BOB_SPEED * (float) speedFrac * (float) Math.cos(phase * 2);

        for (Entity passenger : base.getPassengers()) {
            if (passenger instanceof ItemDisplay display) {
                display.setRotation(yaw, smoothTilt);
                applySway(display, type, smoothRoll, bob);
            }
        }
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
        ItemDisplay body = base.getPassengers().stream()
            .filter(e -> e instanceof ItemDisplay).map(e -> (ItemDisplay) e)
            .findFirst().orElse(null);
        positionSeats(base, type, body, seats, yaw);
    }

    /** Shortest signed distance between two yaws, in [-180, 180). */
    private static float wrapDegrees(double degrees) {
        double d = ((degrees + 180) % 360 + 360) % 360 - 180;
        return (float) d;
    }

    /** Rolls and bobs the model on its own transform - a visual-only shake
     *  layered on top of the base offset/scale from the CarType. The pig and
     *  the seat stands never see it, so driving physics stay untouched. */
    private void applySway(ItemDisplay display, CarType type, float rollDeg, float bobY) {
        org.joml.AxisAngle4f leftRotation = new org.joml.AxisAngle4f(
            (float) Math.toRadians(rollDeg), 0f, 0f, 1f); // roll about forward axis
        display.setTransformation(new org.bukkit.util.Transformation(
            new org.joml.Vector3f((float) type.offsetX,
                (float) type.offsetY + bobY, (float) type.offsetZ),
            leftRotation,
            new org.joml.Vector3f((float) type.scale, (float) type.scale, (float) type.scale),
            new org.joml.AxisAngle4f(0, 0, 0, 1)));
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

    /** Seats are placed where the model says they are, anchored to the REAL
     *  display position (no guessed attach heights). Rider height fine-tuning:
     *  global seat-y-adjust in config.yml, per-type via /car edit - the
     *  per-type value applies live, no respawn needed. */
    private void positionSeats(Pig base, CarType type, ItemDisplay body, List<ArmorStand> seats, float yaw) {
        double radians = Math.toRadians(yaw);
        // model +Z -> forward, model +X rotated consistently with the display
        Vector axisZ = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        Vector axisX = new Vector(Math.cos(radians), 0, Math.sin(radians));
        boolean fromModel = !type.seatOffsets.isEmpty();
        // where the model origin actually is in the world
        double originY = (body != null ? body.getLocation().getY() : base.getLocation().getY() + 0.9)
            + type.offsetY;
        double riderOffset = plugin.getConfig().getDouble("seat-y-adjust", -0.72);
        for (ArmorStand seat : seats) {
            int index = seat.getPersistentDataContainer().getOrDefault(plugin.seatKey(), PersistentDataType.INTEGER, 0);
            double[] off = seatOffset(type, index);
            double s = fromModel ? type.scale : 1.0;
            double seatY = fromModel
                ? originY + off[1] * s + riderOffset + type.seatYAdjust
                : base.getLocation().getY() + off[1] + type.seatYAdjust;
            Location target = base.getLocation().clone()
                .add(axisX.clone().multiply(off[0] * s))
                .add(axisZ.clone().multiply(off[2] * s));
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
