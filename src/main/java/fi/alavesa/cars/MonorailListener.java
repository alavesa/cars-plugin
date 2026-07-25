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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Riding a monorail cart: right-click it to board (sneak to hop off, vanilla). Monorail
 *  entities never take damage - /car monorail remove clears them. */
public final class MonorailListener implements Listener {

    private final CarsPlugin plugin;
    private final MonorailManager mono;

    public MonorailListener(CarsPlugin plugin, MonorailManager mono) {
        this.plugin = plugin;
        this.mono = mono;
    }

    @EventHandler
    public void onMount(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Pig base = resolveCart(event.getRightClicked());
        if (base == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getVehicle() != null) return;
        ArmorStand seat = seatOf(base);
        if (seat == null) {
            Msg.actionbar(player, Component.text("This cart has no seat.", NamedTextColor.GRAY));
            return;
        }
        if (!seat.getPassengers().isEmpty()) {
            Msg.actionbar(player, Component.text("The cart is occupied.", NamedTextColor.GRAY));
            return;
        }
        seat.addPassenger(player);
        Msg.actionbar(player, Component.text("Aboard the monorail. Sneak to step off.", NamedTextColor.GRAY));
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        var tags = event.getEntity().getScoreboardTags();
        if (tags.contains(MonorailManager.TAG_MONO) || tags.contains(MonorailManager.TAG_MONO_PART)
            || tags.contains(MonorailManager.TAG_MONO_SEAT) || tags.contains(MonorailManager.TAG_RAIL)) {
            event.setCancelled(true);
        }
    }

    private Pig resolveCart(Entity clicked) {
        if (clicked instanceof Pig pig && pig.getScoreboardTags().contains(MonorailManager.TAG_MONO)) {
            return pig;
        }
        if (clicked instanceof Interaction i
            && i.getScoreboardTags().contains(MonorailManager.TAG_MONO_PART)
            && i.getVehicle() instanceof Pig pig) {
            return pig;
        }
        if (clicked instanceof ArmorStand seat
            && seat.getScoreboardTags().contains(MonorailManager.TAG_MONO_SEAT)) {
            String baseId = seat.getPersistentDataContainer().get(mono.baseKey, PersistentDataType.STRING);
            if (baseId != null && Bukkit.getEntity(UUID.fromString(baseId)) instanceof Pig pig) return pig;
        }
        return null;
    }

    private ArmorStand seatOf(Pig base) {
        String baseId = base.getUniqueId().toString();
        for (ArmorStand seat : base.getWorld().getEntitiesByClass(ArmorStand.class)) {
            if (!seat.getScoreboardTags().contains(MonorailManager.TAG_MONO_SEAT)) continue;
            String owner = seat.getPersistentDataContainer().get(mono.baseKey, PersistentDataType.STRING);
            if (owner != null && owner.equals(baseId)) return seat;
        }
        return null;
    }
}
