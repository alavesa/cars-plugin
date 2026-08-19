package fi.alavesa.cars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WINCH: a tool taken from a cargo vehicle to reel loose barrels/crates onto the car.
 *
 * MVP + honest limits (visuals need an in-game look):
 *  - Taking it (/car winch next to a cargo car) puts a TRIDENT "Winch" in the main hand. A trident is the
 *    only vanilla item that gives the spear/aiming pose - but only while the player right-click-HOLDS it,
 *    so the spear pose shows while aiming, not permanently (that would need a rig, which was removed).
 *  - A red "hose" ray is drawn from the hand each tick while the winch is out.
 *  - RIGHT-CLICK a barrel block: it's reeled in - a block display flies from the barrel to the nearest
 *    cargo car and becomes a stacked cargo box on its bed. LEFT-CLICK cancels/stows the winch.
 *  - Forklifts do the same automatically for nearby barrels (see DriveTask).
 *  - SCP-1079 crates showing a different model/spot is a follow-up (needs how the 1079 crate is represented
 *    as a placed block - flagged, not guessed).
 */
public final class WinchManager implements Listener, Runnable {

    private final CarsPlugin plugin;
    private final org.bukkit.NamespacedKey winchKey;
    private final Set<UUID> holders = ConcurrentHashMap.newKeySet();

    public WinchManager(CarsPlugin plugin) {
        this.plugin = plugin;
        this.winchKey = new org.bukkit.NamespacedKey(plugin, "winch");
    }

    /** /car winch - take the winch from a nearby cargo car, or stow it if already held. */
    public void toggle(Player p) {
        if (isWinch(p.getInventory().getItemInMainHand())) { stow(p); return; }
        Pig car = plugin.nearestCargoCar(p.getLocation(), 6);
        if (car == null) { Msg.actionbar(p, Component.text("Stand next to a cargo vehicle to take its winch.", NamedTextColor.GRAY)); return; }
        if (!p.getInventory().getItemInMainHand().getType().isAir()) {
            Msg.actionbar(p, Component.text("Empty your main hand to take the winch.", NamedTextColor.GRAY)); return;
        }
        ItemStack winch = new ItemStack(Material.TRIDENT);
        ItemMeta m = winch.getItemMeta();
        m.displayName(Component.text("Winch", NamedTextColor.RED));
        m.getPersistentDataContainer().set(winchKey, PersistentDataType.BYTE, (byte) 1);
        m.setUnbreakable(true);
        winch.setItemMeta(m);
        p.getInventory().setItemInMainHand(winch);
        holders.add(p.getUniqueId());
        Msg.actionbar(p, Component.text("Winch out. Right-click a barrel to reel it in; left-click to stow.", NamedTextColor.GRAY));
    }

    private void stow(Player p) {
        holders.remove(p.getUniqueId());
        if (isWinch(p.getInventory().getItemInMainHand())) p.getInventory().setItemInMainHand(null);
        Msg.actionbar(p, Component.text("Winch stowed.", NamedTextColor.GRAY));
    }

    private boolean isWinch(ItemStack it) {
        return it != null && it.getType() == Material.TRIDENT && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(winchKey, PersistentDataType.BYTE);
    }

    /** Red "hose" ray from the hand each tick, plus keep the holder set honest. */
    @Override
    public void run() {
        for (UUID id : holders.toArray(new UUID[0])) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline() || !isWinch(p.getInventory().getItemInMainHand())) { holders.remove(id); continue; }
            drawRay(p);
        }
    }

    private void drawRay(Player p) {
        Location start = p.getEyeLocation().add(p.getLocation().getDirection().rotateAroundY(-0.4).multiply(0.4)).subtract(0, 0.3, 0);
        Vector dir = p.getEyeLocation().getDirection().normalize().multiply(0.4);
        var dust = new Particle.DustOptions(Color.fromRGB(220, 30, 30), 0.9f);
        Location at = start.clone();
        for (int i = 0; i < 12; i++) {   // ~5-block red hose forward from the hand
            p.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
            at.add(dir);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!isWinch(event.getItem())) return;
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            stow(p);   // left-click cancels the winch
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            event.setCancelled(true);
            if (event.getClickedBlock().getType() == Material.BARREL) {
                reelIn(p, event.getClickedBlock());          // load: reel this barrel onto a car
            } else {
                unload(p, event.getClickedBlock().getRelative(event.getBlockFace()));  // unload: drop a barrel here
            }
        }
    }

    /** Drop a barrel from the nearest cargo car back into the world at {@code where}. If the box carried a
     *  Terminal shady-barrel registration, it's relocated here so deliveries follow the barrel. */
    private void unload(Player p, Block where) {
        if (!where.getType().isAir() && !where.isReplaceable()) {
            Msg.actionbar(p, Component.text("No room to set the barrel down there.", NamedTextColor.GRAY)); return;
        }
        Pig car = plugin.nearestCargoCar(p.getLocation(), 8);
        if (car == null) { Msg.actionbar(p, Component.text("No cargo vehicle nearby.", NamedTextColor.GRAY)); return; }
        BlockDisplay box = null;
        for (org.bukkit.entity.Entity e : car.getPassengers()) {
            if (e instanceof BlockDisplay bd && bd.getScoreboardTags().contains(CarsPlugin.TAG_CARGOBOX)) box = bd;   // last one on
        }
        if (box == null) { Msg.actionbar(p, Component.text("That vehicle has no barrels to unload.", NamedTextColor.GRAY)); return; }
        String shadyKey = box.getPersistentDataContainer().get(plugin.boxShadyKey(), PersistentDataType.STRING);
        box.remove();
        where.setType(Material.BARREL);
        where.getWorld().playSound(where.getLocation(), org.bukkit.Sound.BLOCK_CHAIN_PLACE, 0.9f, 1.0f);
        if (shadyKey != null) {
            boolean moved = ShadyBridge.relocate(shadyKey, where.getLocation());
            if (moved) Msg.actionbar(p, Component.text("Delivery barrel relocated - shady app deliveries follow it here.", NamedTextColor.GRAY));
        }
    }

    /** The trident must never actually be thrown. */
    @EventHandler
    public void onThrow(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Trident t
            && t.getShooter() instanceof Player p && holders.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Reel a barrel block onto the nearest cargo car: fly a block display from the barrel to the car, then
     *  land it as a stacked cargo box. Public so forklifts (DriveTask) can trigger it automatically. */
    public void reelIn(Player winchHolder, Block barrel) {
        Pig car = plugin.nearestCargoCar(barrel.getLocation(), 12);
        if (car == null) {
            if (winchHolder != null) Msg.actionbar(winchHolder, Component.text("No cargo vehicle nearby to reel it onto.", NamedTextColor.GRAY));
            return;
        }
        reelBlockToCar(barrel, car);
    }

    /** Shared reel-in animation used by the winch and by forklift auto-pickup. */
    public void reelBlockToCar(Block barrel, Pig car) {
        if (barrel.getType() != Material.BARREL) return;
        Location from = barrel.getLocation().toCenterLocation();
        // If this barrel is a Terminal shady-app delivery spot, carry its registration on the box so it can
        // be relocated when the barrel is dropped again - the shady app keeps working after transport.
        final String shadyKey = ShadyBridge.barrelKeyAt(barrel.getLocation());
        barrel.setType(Material.AIR);   // the world barrel is consumed; it now rides the car
        BlockDisplay fly = from.getWorld().spawn(from, BlockDisplay.class, d -> {
            d.setBlock(Material.BARREL.createBlockData());
            d.setTeleportDuration(3);
            d.setTransformation(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f), new AxisAngle4f(),
                new Vector3f(1, 1, 1), new AxisAngle4f()));
        });
        from.getWorld().playSound(from, org.bukkit.Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.2f);
        // fly it to the car over ~1s, then hand it to the car as a stacked cargo box
        final int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            if (fly.isDead() || car.isDead() || plugin.isWrecked(car) || t[0] > 20) {
                fly.remove();
                if (!car.isDead() && !plugin.isWrecked(car)) plugin.addWinchedCargo(car, shadyKey);
                task[0].cancel();
                return;
            }
            Location target = car.getLocation().add(0, 1.2, 0);
            Location cur = fly.getLocation();
            fly.teleport(cur.add(target.toVector().subtract(cur.toVector()).multiply(0.25)));
        }, 1L, 1L);
    }
}
