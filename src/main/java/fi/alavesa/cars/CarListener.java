package fi.alavesa.cars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public final class CarListener implements Listener {

    private final CarsPlugin plugin;
    private final DriveTask task;

    public CarListener(CarsPlugin plugin, DriveTask task) {
        this.plugin = plugin;
        this.task = task;
    }

    /** Live WASD state - this is what makes the driving feel like driving. */
    @EventHandler
    public void onInput(PlayerInputEvent event) {
        task.input(event.getPlayer(), event.getInput());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        task.forget(event.getPlayer().getUniqueId());
    }

    /** Click the car: driver's seat first, then passenger seats until full. */
    @EventHandler
    public void onMount(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Pig base = resolveCar(event.getRightClicked());
        if (base == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getVehicle() != null) return;
        var seats = task.collectSeats(base);
        for (ArmorStand seat : seats) {
            if (!seat.getPassengers().isEmpty()) continue;
            if (seat.getLocation().distanceSquared(base.getLocation()) > 25) continue;
            seat.addPassenger(player);
            int index = seat.getPersistentDataContainer().getOrDefault(
                plugin.seatKey(), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
            Msg.actionbar(player, Component.text(index == 0
                ? "You're driving. WASD; sneak at speed = handbrake drift; slow down + sneak to get out."
                : "Passenger seat. Sneak to get out.", NamedTextColor.GRAY));
            return;
        }
        Msg.actionbar(player, Component.text("The car is full.", NamedTextColor.GRAY));
    }

    private Pig resolveCar(Entity clicked) {
        if (clicked instanceof Pig pig && pig.getScoreboardTags().contains(DriveTask.TAG_CAR)) {
            return pig;
        }
        if (clicked instanceof Interaction interaction
            && interaction.getScoreboardTags().contains(DriveTask.TAG_PART)
            && interaction.getVehicle() instanceof Pig pig) {
            return pig;
        }
        if (clicked instanceof ArmorStand seat
            && seat.getScoreboardTags().contains(DriveTask.TAG_SEAT)) {
            String carId = seat.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
            if (carId != null && Bukkit.getEntity(UUID.fromString(carId)) instanceof Pig pig) {
                return pig;
            }
        }
        return null;
    }

    /**
     * Sneak is the drift HANDBRAKE, but vanilla treats sneak as "get out of the
     * vehicle". So for the DRIVER's seat, cancel the dismount while the car is
     * moving at speed - holding sneak then drifts instead of ejecting you. Slow
     * below {@link DriveTask#HANDBRAKE_MIN_SPEED} to actually step out. Passengers
     * (any other seat) always exit on sneak.
     */
    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getDismounted() instanceof ArmorStand seat)) return;
        if (!seat.getScoreboardTags().contains(DriveTask.TAG_SEAT)) return;
        int idx = seat.getPersistentDataContainer().getOrDefault(
            plugin.seatKey(), PersistentDataType.INTEGER, -1);
        if (idx != 0) return;   // only the driver's seat gets handbrake behaviour
        String carId = seat.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
        if (carId == null) return;
        if (task.carSpeed(UUID.fromString(carId)) >= DriveTask.HANDBRAKE_MIN_SPEED) {
            event.setCancelled(true);   // sneak = handbrake, not exit, while moving
        }
    }

    /** Cars don't take damage - /car remove is the scrapyard. */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        var tags = event.getEntity().getScoreboardTags();
        if (tags.contains(DriveTask.TAG_CAR) || tags.contains(DriveTask.TAG_PART)
            || tags.contains(DriveTask.TAG_SEAT)) {
            event.setCancelled(true);
        }
    }
}
