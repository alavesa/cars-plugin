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
            player.sendActionBar(Component.text(index == 0
                ? "You're driving. WASD; sneak to get out."
                : "Passenger seat. Sneak to get out.", NamedTextColor.GRAY));
            return;
        }
        player.sendActionBar(Component.text("The car is full.", NamedTextColor.GRAY));
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
