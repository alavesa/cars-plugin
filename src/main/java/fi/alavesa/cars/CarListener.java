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

    /** Click the car: sneak+click opens the cargo hold (if any), otherwise take a seat. */
    @EventHandler
    public void onMount(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Pig base = resolveCar(event.getRightClicked());
        if (base == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        CarType type = plugin.typeOf(base);
        // A wreck is inert - you can still open its cargo but you can't drive or sit in it.
        if (plugin.isWrecked(base)) {
            if (type != null && type.cargoRows > 0) plugin.openCargo(player, base, type);
            else Msg.actionbar(player, Component.text("This car is wrecked.", NamedTextColor.RED));
            return;
        }
        // Sneak + click a cargo vehicle (forklift/truck) opens its storage instead of seating you.
        if (player.isSneaking() && type != null && type.cargoRows > 0) {
            plugin.openCargo(player, base, type);
            return;
        }
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

    /** Cars have REAL (hidden) health on the base pig. Gun raycasts hit the pig directly (it's the only
     *  living car part); punches on the hitbox are fed in via the attack poll (DriveTask). Combat damage
     *  lowers the real health and wrecks the car at 0; environmental damage and damage to the non-living
     *  parts (display/hitbox/seats) is cancelled so only shooting/punching hurts the car. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        var tags = event.getEntity().getScoreboardTags();
        // The living base pig = the health pool.
        if (event.getEntity() instanceof Pig pig && tags.contains(DriveTask.TAG_CAR)) {
            if (plugin.isWrecked(pig)) { event.setCancelled(true); return; }
            if (!isCombat(event.getCause())) { event.setCancelled(true); return; }   // immune to fall/fire/drown/etc.
            Player source = damagerOf(event);
            boolean fatal = plugin.onCarHealthDamage(pig, event.getFinalDamage(), source);
            if (fatal) event.setCancelled(true);   // we wreck it ourselves; don't let vanilla "kill" the pig
            return;
        }
        // The display / hitbox / seats are cosmetic - never let anything hurt or knock them.
        if (tags.contains(DriveTask.TAG_PART) || tags.contains(DriveTask.TAG_SEAT)) event.setCancelled(true);
    }

    private static boolean isCombat(EntityDamageEvent.DamageCause c) {
        return c == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            || c == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
            || c == EntityDamageEvent.DamageCause.PROJECTILE
            || c == EntityDamageEvent.DamageCause.CUSTOM;   // gun bypass-pvp path lands as CUSTOM/source-less
    }

    private static Player damagerOf(EntityDamageEvent event) {
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player p) return p;
            if (byEntity.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        }
        return null;
    }

    /** Open the cargo hold while SEATED in a cargo vehicle. Minecraft fires no server event when a player
     *  presses E to open their own inventory, so we use the swap-hands key (F) - the reliable seated key -
     *  to open the cargo instead. Cancels the swap so no offhand juggling happens. */
    @EventHandler
    public void onSeatedCargoKey(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!(player.getVehicle() instanceof ArmorStand seat)
            || !seat.getScoreboardTags().contains(DriveTask.TAG_SEAT)) return;
        String carId = seat.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
        if (carId == null || !(Bukkit.getEntity(UUID.fromString(carId)) instanceof Pig base)) return;
        CarType type = plugin.typeOf(base);
        if (type == null || type.cargoRows <= 0) return;
        event.setCancelled(true);
        plugin.openCargo(player, base, type);
    }

    /** Persist the cargo hold when its window closes. */
    @EventHandler
    public void onCargoClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CarsPlugin.CargoHolder holder)) return;
        if (Bukkit.getEntity(holder.carId()) instanceof Pig base) {
            plugin.saveCargo(base, event.getInventory());
        }
    }

    /** Resolve the base Pig from any car part (Pig / ItemDisplay / Interaction / seat). */
    private Pig carBaseOf(Entity entity) {
        if (entity instanceof Pig pig && pig.getScoreboardTags().contains(DriveTask.TAG_CAR)) return pig;
        if (entity.getVehicle() instanceof Pig pig && pig.getScoreboardTags().contains(DriveTask.TAG_CAR)) return pig;
        if (entity instanceof ArmorStand seat && seat.getScoreboardTags().contains(DriveTask.TAG_SEAT)) {
            String carId = seat.getPersistentDataContainer().get(plugin.carKey(), PersistentDataType.STRING);
            if (carId != null && Bukkit.getEntity(UUID.fromString(carId)) instanceof Pig pig) return pig;
        }
        return null;
    }
}
