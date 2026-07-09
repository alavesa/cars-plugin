package fi.alavesa.cars;

import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.World;
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
            // once per runtime: velocity needs AI on, and vanilla goals
            // (wander, panic, follow players holding carrots) must go
            base.setAI(true);
            Bukkit.getMobGoals().removeAllGoals(base);
        }
        CarType type = plugin.registry().get(
            base.getPersistentDataContainer().getOrDefault(plugin.typeKey(), PersistentDataType.STRING, ""));
        if (type == null) return;

        Player driver = base.getPassengers().stream()
            .filter(e -> e instanceof Player).map(e -> (Player) e)
            .findFirst().orElse(null);
        double speed = speeds.getOrDefault(base.getUniqueId(), 0.0);
        float yaw = base.getLocation().getYaw();

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
        speeds.put(base.getUniqueId(), speed);

        double radians = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        Vector velocity = forward.clone().multiply(speed / 20.0);
        velocity.setY(Math.min(0.1, base.getVelocity().getY())); // gravity keeps working
        base.setVelocity(velocity);
        base.setRotation(yaw, 0);

        for (Entity passenger : base.getPassengers()) {
            if (passenger instanceof ItemDisplay display) display.setRotation(yaw, 0);
        }
        if (Math.abs(speed) > 0.4 && tick % 6 == 0) {
            float pitch = (float) (0.6 + Math.abs(speed) / type.maxSpeed);
            base.getWorld().playSound(base.getLocation(), type.sound, 0.7f, pitch);
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
