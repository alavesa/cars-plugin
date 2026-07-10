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

    /** Seat offsets (right, forward) by seat index (0 = driver rides the pig). */
    private static final double[][] SEATS = {{-0.55, -0.1}, {0.55, -0.9}, {-0.55, -0.9}};

    private final CarsPlugin plugin;
    private final Map<UUID, Double> speeds = new HashMap<>();
    private final Map<UUID, Input> inputs = new HashMap<>();
    private final Set<UUID> prepared = new HashSet<>();
    private final Map<UUID, Vector> momentum = new HashMap<>();
    private final Map<UUID, Float> yaws = new HashMap<>();
    private final Map<UUID, Double> lastY = new HashMap<>();
    private final Map<UUID, Float> tilt = new HashMap<>();

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

        Player driver = base.getPassengers().stream()
            .filter(e -> e instanceof Player).map(e -> (Player) e)
            .findFirst().orElse(null);
        double speed = speeds.getOrDefault(base.getUniqueId(), 0.0);
        // OUR yaw is the steering state. The entity's own yaw is never read
        // back: vanilla rotates a ridden mob's body toward its velocity, and
        // reading that back fed our steering into itself - the death spin.
        float yaw = yaws.computeIfAbsent(base.getUniqueId(),
            id -> base.getLocation().getYaw());

        if (driver != null) {
            Input input = inputs.get(driver.getUniqueId());
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
        Vector desired = forward.clone().multiply(speed / 20.0);
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

        for (Entity passenger : base.getPassengers()) {
            if (passenger instanceof ItemDisplay display) display.setRotation(yaw, smoothTilt);
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
        positionSeats(base, yaw);
    }

    /** Passenger seats trail the car on velocity, one tick behind at most. */
    private void positionSeats(Pig base, float yaw) {
        double radians = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        for (ArmorStand seat : base.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!seat.getScoreboardTags().contains(TAG_SEAT)) continue;
            String carId = seat.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
            if (carId == null || !carId.equals(base.getUniqueId().toString())) continue;
            int index = seat.getPersistentDataContainer().getOrDefault(plugin.seatKey(), PersistentDataType.INTEGER, 1);
            double[] offset = SEATS[Math.min(index - 1, SEATS.length - 1)];
            Location target = base.getLocation().clone()
                .add(right.clone().multiply(offset[0]))
                .add(forward.clone().multiply(offset[1]))
                .add(0, 0.1, 0);
            Vector delta = target.toVector().subtract(seat.getLocation().toVector());
            if (delta.lengthSquared() > 36 && seat.getPassengers().isEmpty()) {
                seat.teleport(target); // desynced empty seat snaps back
            } else {
                seat.setVelocity(delta);
                seat.setRotation(yaw, 0);
            }
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
