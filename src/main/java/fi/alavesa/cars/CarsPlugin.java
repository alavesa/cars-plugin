package fi.alavesa.cars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public final class CarsPlugin extends JavaPlugin {

    private CarRegistry registry;
    private DriveTask task;
    private MonorailManager monorail;
    private MonorailTask monoTask;
    private NamespacedKey typeKey;
    private NamespacedKey carKey;
    private NamespacedKey seatKey;
    private NamespacedKey healthKey;    // current hit points, on the base Pig
    private NamespacedKey cargoKey;     // serialized cargo contents, on the base Pig
    private NamespacedKey wreckedKey;   // 1 once destroyed, on the base Pig
    private NamespacedKey attackKey;    // last-seen attack timestamp, on the Interaction hitbox
    private NamespacedKey winchCountKey;// how many barrels have been winched onto a car, on the base Pig
    private NamespacedKey boxShadyKey;  // a cargo box's shady-barrel origin key, on the BlockDisplay box
    private NamespacedKey modelKey;     // UUID of the external tagged model entity this car follows, on the base Pig
    private WinchManager winch;

    /** How close (blocks) an externally-spawned tagged entity has to be for a car to claim it as its model. */
    public static final double MODEL_CLAIM_RADIUS = 6.0;

    @Override
    public void onEnable() {
        typeKey = new NamespacedKey(this, "type");
        carKey = new NamespacedKey(this, "car");
        seatKey = new NamespacedKey(this, "seat");
        healthKey = new NamespacedKey(this, "health");
        cargoKey = new NamespacedKey(this, "cargo");
        wreckedKey = new NamespacedKey(this, "wrecked");
        attackKey = new NamespacedKey(this, "last_attack");
        winchCountKey = new NamespacedKey(this, "winch_count");
        boxShadyKey = new NamespacedKey(this, "box_shady");
        modelKey = new NamespacedKey(this, "model_entity");
        getConfig().addDefault("seat-y-adjust", -0.72);
        // When a car is removed/scrapped, the external model entity it was following is, by default, LEFT
        // in place (the op owns it - they brought it in from BetterModel/ModelEngine/an armour stand).
        // Set true to also delete the model when the car is scrapped with /car remove.
        getConfig().addDefault("delete-model-on-remove", false);
        getConfig().addDefault("monorail.speed", 0.3);
        getConfig().addDefault("monorail.arrive", 0.7);
        getConfig().addDefault("monorail.dwell-ticks", 40);
        getConfig().addDefault("monorail.seat-y", 0.4);
        getConfig().addDefault("monorail.rail-spacing", 1.0);
        getConfig().addDefault("monorail.max-rail-pieces", 4000);
        getConfig().options().copyDefaults(true);
        saveConfig();
        registry = new CarRegistry(this);
        registry.load();
        task = new DriveTask(this);
        monorail = new MonorailManager(this);
        monoTask = new MonorailTask(this, monorail);
        winch = new WinchManager(this);
        task.setWinch(winch);
        getServer().getPluginManager().registerEvents(new CarListener(this, task), this);
        getServer().getPluginManager().registerEvents(new MonorailListener(this, monorail), this);
        getServer().getPluginManager().registerEvents(winch, this);
        getServer().getScheduler().runTaskTimer(this, task, 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, monoTask, 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, winch, 20L, 1L);   // red-hose ray each tick
        getLogger().info("Cars enabled - " + registry.all().size() + " vehicle type(s), "
            + monorail.all().size() + " monorail line(s)");
    }

    public CarRegistry registry() { return registry; }
    public NamespacedKey typeKey() { return typeKey; }
    public NamespacedKey carKey() { return carKey; }
    public NamespacedKey seatKey() { return seatKey; }
    public NamespacedKey healthKey() { return healthKey; }
    public NamespacedKey cargoKey() { return cargoKey; }
    public NamespacedKey wreckedKey() { return wreckedKey; }
    public NamespacedKey attackKey() { return attackKey; }
    public NamespacedKey modelKey() { return modelKey; }

    // ------------------------------------------------------------- spawning

    public void spawnCar(CarType type, Location location) {
        location = location.clone();
        location.setPitch(0);
        Pig base = location.getWorld().spawn(location, Pig.class, pig -> {
            pig.setInvisible(true);
            pig.setSilent(true);
            pig.setPersistent(true);
            pig.setRemoveWhenFarAway(false);
            pig.setAdult();
            pig.customName(Component.text(type.name, NamedTextColor.GRAY));
            pig.setCustomNameVisible(false);
            pig.getAttribute(Attribute.STEP_HEIGHT).setBaseValue(1.1);
            pig.addScoreboardTag(DriveTask.TAG_CAR);
            pig.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id);
            // REAL, hidden Minecraft health: the pig (the only living car part, and what gun raycasts hit)
            // carries the car's hit points. A pig shows no health bar, so it stays invisible. Knockback is
            // resisted so a hit never shoves the car; environmental damage is cancelled in CarListener.
            double hp = Math.max(1.0, Math.min(1024.0, type.maxHealth));
            pig.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
            pig.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            pig.setHealth(hp);
        });
        // AI must stay ON (NoAI freezes velocity processing entirely), but
        // aware=false stops all of the pig's own decision-making - and unlike
        // runtime goal-stripping it persists across chunk reloads, so the car
        // never reverts to wandering farm animal.
        base.setAware(false);
        org.bukkit.Bukkit.getMobGoals().removeAllGoals(base);
        // NO plugin-owned body model any more: the visible car is an external entity carrying this type's
        // scoreboard TAG (BetterModel/ModelEngine/armour stand/item display - anything with the tag). The car
        // CLAIMS the nearest such entity below and DriveTask teleports it onto the car each tick.
        Interaction hitbox = location.getWorld().spawn(location.clone().add(0, type.hitboxOffsetY, 0), Interaction.class, i -> {
            i.setInteractionWidth((float) type.hitboxWidth);
            i.setInteractionHeight((float) type.hitboxHeight);
            i.setPersistent(true);
            i.addScoreboardTag(DriveTask.TAG_PART);
        });
        base.addPassenger(hitbox);
        int seatCount = Math.max(1, type.seatOffsets.size());
        for (int seat = 0; seat < seatCount; seat++) {
            spawnSeat(base, seat);
        }
        spawnCargoBoxes(base, type);
        if (type.hasWinchSpot) {
            Interaction winchBox = location.getWorld().spawn(location.clone().add(type.winchX, type.winchY, type.winchZ),
                Interaction.class, i -> {
                    i.setInteractionWidth(0.6f);
                    i.setInteractionHeight(0.6f);
                    i.setPersistent(true);
                    i.addScoreboardTag(DriveTask.TAG_PART);
                    i.addScoreboardTag(TAG_WINCHBOX);
                });
            base.addPassenger(winchBox);
        }
        // Grab a tagged model if one is already standing by; if not, DriveTask keeps re-trying each tick, so
        // the op can spawn the model right after the car.
        tryClaimModel(base, type);
    }

    public static final String TAG_WINCHBOX = "cars.winchbox";

    // ------------------------------------------------------- external model follow

    /** The external entity this car currently follows, or null if none claimed / it has gone away. */
    public Entity claimedModel(Pig base) {
        String id = base.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        if (id == null) return null;
        try { return Bukkit.getEntity(UUID.fromString(id)); } catch (IllegalArgumentException e) { return null; }
    }

    /** Stop following whatever model this car had claimed (the model entity itself is left untouched). */
    public void releaseModel(Pig base) {
        base.getPersistentDataContainer().remove(modelKey);
    }

    /** All model-entity UUIDs already claimed by a car in this world, so two cars never fight over one. */
    private java.util.Set<UUID> claimedModels(World world) {
        java.util.Set<UUID> claimed = new java.util.HashSet<>();
        for (Pig pig : world.getEntitiesByClass(Pig.class)) {
            if (!pig.getScoreboardTags().contains(DriveTask.TAG_CAR)) continue;
            String id = pig.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
            if (id != null) try { claimed.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
        }
        return claimed;
    }

    /** Claim the nearest unclaimed entity carrying this type's tag within {@link #MODEL_CLAIM_RADIUS}, storing
     *  its UUID on the base Pig. Returns the claimed entity, or null if none is available yet (the car will
     *  re-try next tick). Our own car parts are never claimed even if they happened to share the tag. */
    public Entity tryClaimModel(Pig base, CarType type) {
        if (type.tag == null || type.tag.isEmpty()) return null;
        Location at = base.getLocation();
        java.util.Set<UUID> taken = claimedModels(at.getWorld());
        Entity best = null; double bestSq = MODEL_CLAIM_RADIUS * MODEL_CLAIM_RADIUS;
        for (Entity e : at.getWorld().getNearbyEntities(at, MODEL_CLAIM_RADIUS, MODEL_CLAIM_RADIUS, MODEL_CLAIM_RADIUS)) {
            if (e.equals(base) || !e.getScoreboardTags().contains(type.tag)) continue;
            var tags = e.getScoreboardTags();
            if (tags.contains(DriveTask.TAG_CAR) || tags.contains(DriveTask.TAG_PART)
                || tags.contains(DriveTask.TAG_SEAT)) continue;   // never claim our own parts
            if (taken.contains(e.getUniqueId())) continue;
            double d = e.getLocation().distanceSquared(at);
            if (d < bestSq) { bestSq = d; best = e; }
        }
        if (best != null) base.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING,
            best.getUniqueId().toString());
        return best;
    }

    /** Spawn one visible cargo box (a plain barrel block display) per configured position, riding the car. */
    public void spawnCargoBoxes(Pig base, CarType type) {
        for (double[] box : type.cargoBoxes) {
            Transformation xf = new Transformation(
                new Vector3f((float) box[0], (float) box[1], (float) box[2]),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f((float) type.cargoBoxScale, (float) type.cargoBoxScale, (float) type.cargoBoxScale),
                new AxisAngle4f(0, 0, 0, 1));
            org.bukkit.entity.BlockDisplay disp = base.getWorld().spawn(base.getLocation(),
                org.bukkit.entity.BlockDisplay.class, d -> {
                    d.setBlock(Material.BARREL.createBlockData());
                    d.setPersistent(true); d.setTeleportDuration(1); d.setTransformation(xf);
                    d.addScoreboardTag(DriveTask.TAG_PART); d.addScoreboardTag(TAG_CARGOBOX);
                });
            base.addPassenger(disp);
        }
    }

    public static final String TAG_CARGOBOX = "cars.cargobox";

    /** Despawn a car's cargo boxes (used when re-applying positions or wrecking). */
    public void clearCargoBoxes(Pig base) {
        for (Entity p : new java.util.ArrayList<>(base.getPassengers())) {
            if (p.getScoreboardTags().contains(TAG_CARGOBOX)) p.remove();
        }
        for (Entity p : base.getWorld().getNearbyEntities(base.getLocation(), 3, 3, 3)) {
            if (p.getScoreboardTags().contains(TAG_CARGOBOX) && p.getVehicle() == base) p.remove();
        }
    }

    /** One seat stand; also used to retrofit a driver's seat onto old cars. */
    public void spawnSeat(Pig base, int index) {
        base.getWorld().spawn(base.getLocation().clone().add(0, 0.1, 0), ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setPersistent(true);
            stand.setSmall(true);
            stand.setInvulnerable(true);
            stand.addScoreboardTag(DriveTask.TAG_SEAT);
            stand.getPersistentDataContainer().set(carKey, PersistentDataType.STRING,
                base.getUniqueId().toString());
            stand.getPersistentDataContainer().set(seatKey, PersistentDataType.INTEGER, index);
        });
    }

    // ------------------------------------------------------- health & wreck

    public CarType typeOf(Pig base) {
        String id = base.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : registry.get(id);
    }

    public boolean isWrecked(Pig base) {
        return base.getPersistentDataContainer().getOrDefault(wreckedKey, PersistentDataType.INTEGER, 0) == 1;
    }

    public Interaction hitboxOf(Pig base) {
        for (Entity p : base.getPassengers()) {
            if (p instanceof Interaction i && i.getScoreboardTags().contains(DriveTask.TAG_PART)) return i;
        }
        return null;
    }

    /** Programmatic car damage (used by the punch poll): routes through the pig's REAL health so it lands
     *  exactly like a gun shot and is handled once, in CarListener.onDamage. */
    public void damageCar(Pig base, double amount, Player source) {
        if (isWrecked(base) || amount <= 0 || base.isDead()) return;
        if (source != null) base.damage(amount, source);
        else base.damage(amount);
    }

    /** Feedback + wreck decision for a hit that reached the car's real health. Returns true if the hit was
     *  fatal (and the car has been wrecked) so the caller can cancel the vanilla death. */
    public boolean onCarHealthDamage(Pig base, double amount, Player source) {
        if (isWrecked(base)) return true;   // a wreck absorbs nothing more
        CarType type = typeOf(base);
        double max = type != null ? type.maxHealth : base.getAttribute(Attribute.MAX_HEALTH).getValue();
        double remaining = base.getHealth() - amount;
        base.getWorld().playSound(base.getLocation(), org.bukkit.Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.7f, 1.4f);
        base.getWorld().spawnParticle(org.bukkit.Particle.CRIT, base.getLocation().add(0, 1, 0), 8, 0.6, 0.4, 0.6, 0.1);
        if (remaining <= 0) { wreckCar(base); return true; }
        if (remaining <= max * 0.3) {
            base.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, base.getLocation().add(0, 1, 0), 6, 0.5, 0.3, 0.5, 0.02);
        }
        if (source != null) {
            Msg.actionbar(source, Component.text("Car health: " + (int) Math.ceil(remaining) + " / " + (int) max,
                remaining <= max * 0.3 ? NamedTextColor.RED : NamedTextColor.YELLOW));
        }
        return false;
    }

    /** Turn a car into an inert wreck: undrivable, no seats. The external model is RELEASED (left where it
     *  last followed to, like a crashed body) - it is the op's entity, so Cars never deletes it on a wreck. */
    public void wreckCar(Pig base) {
        if (isWrecked(base)) return;
        base.getPersistentDataContainer().set(wreckedKey, PersistentDataType.INTEGER, 1);
        base.getPersistentDataContainer().set(healthKey, PersistentDataType.DOUBLE, 0.0);
        // eject everyone and delete the seats - a wreck can't be driven or ridden
        for (ArmorStand seat : task.collectSeats(base)) {
            for (Entity rider : seat.getPassengers()) if (rider instanceof Player) seat.removePassenger(rider);
            seat.remove();
        }
        base.removeScoreboardTag(DriveTask.TAG_CAR);   // DriveTask stops ticking it; no throttle/steering
        // drop the cargo hold on the ground so it isn't lost inside an unusable wreck, and remove the boxes
        dropCargo(base);
        clearCargoBoxes(base);
        // stop following the external model (its last position becomes the "wreck" where it sits)
        releaseModel(base);
        base.getWorld().playSound(base.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
        base.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, base.getLocation().add(0, 1, 0), 30, 0.8, 0.6, 0.8, 0.05);
    }

    // ----------------------------------------------------------------- winch

    public WinchManager winch() { return winch; }

    /** Nearest non-wrecked cargo-capable car (cargo-rows > 0 or a forklift) within radius, or null. */
    public Pig nearestCargoCar(Location at, double radius) {
        Pig best = null; double bestSq = radius * radius;
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof Pig pig) || !pig.getScoreboardTags().contains(DriveTask.TAG_CAR) || isWrecked(pig)) continue;
            CarType type = typeOf(pig);
            if (type == null || (type.cargoRows <= 0 && !type.forklift)) continue;
            double d = pig.getLocation().distanceSquared(at);
            if (d < bestSq) { bestSq = d; best = pig; }
        }
        return best;
    }

    /** A cargo box's shady-barrel origin key (if the winched barrel was a Terminal delivery barrel). */
    public NamespacedKey boxShadyKey() { return boxShadyKey; }

    /** Land a winched barrel on a car as a stacked cargo box that rides along. Carries the shady-barrel
     *  origin key (or null) so the Terminal delivery marking can follow the barrel when it's dropped again. */
    public void addWinchedCargo(Pig base, String shadyOriginKey) {
        int count = base.getPersistentDataContainer().getOrDefault(winchCountKey, PersistentDataType.INTEGER, 0);
        CarType type = typeOf(base);
        double scale = type != null ? type.cargoBoxScale : 0.6;
        // simple stack along the bed: two per row, rising as it fills
        double bx = (count % 2 == 0 ? -0.4 : 0.4);
        double by = 0.7 + (count / 4) * 0.7;
        double bz = -0.3 + ((count / 2) % 2) * 0.7;
        Transformation xf = new Transformation(new Vector3f((float) bx, (float) by, (float) bz),
            new AxisAngle4f(), new Vector3f((float) scale, (float) scale, (float) scale), new AxisAngle4f());
        org.bukkit.entity.BlockDisplay box = base.getWorld().spawn(base.getLocation(), org.bukkit.entity.BlockDisplay.class, d -> {
            d.setBlock(Material.BARREL.createBlockData());
            d.setPersistent(true); d.setTeleportDuration(1); d.setTransformation(xf);
            d.addScoreboardTag(DriveTask.TAG_PART); d.addScoreboardTag(TAG_CARGOBOX);
            if (shadyOriginKey != null) d.getPersistentDataContainer().set(boxShadyKey, PersistentDataType.STRING, shadyOriginKey);
        });
        base.addPassenger(box);
        base.getPersistentDataContainer().set(winchCountKey, PersistentDataType.INTEGER, count + 1);
        base.getWorld().playSound(base.getLocation(), org.bukkit.Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.9f);
    }

    // ----------------------------------------------------------------- cargo

    /** Open a car's cargo hold, loading its saved contents. */
    public void openCargo(Player player, Pig base, CarType type) {
        int size = Math.max(1, Math.min(6, type.cargoRows)) * 9;
        org.bukkit.inventory.Inventory inv = getServer().createInventory(
            new CargoHolder(base.getUniqueId()), size,
            Component.text(type.name + " — Cargo", NamedTextColor.DARK_GRAY));
        byte[] data = base.getPersistentDataContainer().get(cargoKey, PersistentDataType.BYTE_ARRAY);
        if (data != null && data.length > 0) {
            try {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(data);
                for (int i = 0; i < items.length && i < size; i++) inv.setItem(i, items[i]);
            } catch (Throwable ignored) { }
        }
        player.openInventory(inv);
    }

    /** Persist a cargo inventory back onto the base Pig. */
    public void saveCargo(Pig base, org.bukkit.inventory.Inventory inv) {
        base.getPersistentDataContainer().set(cargoKey, PersistentDataType.BYTE_ARRAY,
            ItemStack.serializeItemsAsBytes(inv.getContents()));
    }

    /** Spill a wrecked car's cargo onto the ground. */
    private void dropCargo(Pig base) {
        byte[] data = base.getPersistentDataContainer().get(cargoKey, PersistentDataType.BYTE_ARRAY);
        if (data == null || data.length == 0) return;
        try {
            for (ItemStack it : ItemStack.deserializeItemsFromBytes(data)) {
                if (it != null && !it.getType().isAir()) base.getWorld().dropItemNaturally(base.getLocation(), it);
            }
        } catch (Throwable ignored) { }
        base.getPersistentDataContainer().remove(cargoKey);
    }

    /** Marks an inventory as a car's cargo hold and remembers which car it belongs to. */
    public record CargoHolder(java.util.UUID carId) implements org.bukkit.inventory.InventoryHolder {
        @Override public org.bukkit.inventory.Inventory getInventory() { return null; }
    }

    // ------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cars.admin")) return error(sender, "No permission.");
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 3) return error(sender,
                    "/car create <id> <tag>   (tag = the scoreboard tag your model entity carries)");
                String id = args[1].toLowerCase(Locale.ROOT);
                if (registry.get(id) != null) return error(sender, "'" + id + "' already exists.");
                CarType type = registry.create(id);
                type.tag = args[2];
                registry.save();
                sender.sendMessage(Component.text("Created vehicle type '" + id + "' following model tag '"
                    + type.tag + "'. Spawn a tagged model near a spawned car, or tune with /car edit " + id + " ...",
                    NamedTextColor.AQUA));
                return true;
            }
            case "seat" -> { return seatCommand(sender, args); }
            case "seats" -> {
                if (args.length < 2) return error(sender, "/car seats <id>");
                CarType type = registry.get(args[1]);
                if (type == null) return error(sender, "No vehicle type '" + args[1] + "'.");
                if (type.seatOffsets.isEmpty()) { sender.sendMessage(Component.text(type.id + " has no seats.", NamedTextColor.GRAY)); return true; }
                for (int i = 0; i < type.seatOffsets.size(); i++) {
                    double[] s = type.seatOffsets.get(i);
                    sender.sendMessage(Component.text("  " + (i == 0 ? "driver" : "seat " + i) + ": "
                        + s[0] + " " + s[1] + " " + s[2], NamedTextColor.AQUA));
                }
                return true;
            }
            case "edit" -> {
                if (args.length < 4) return usage(sender);
                CarType type = registry.get(args[1]);
                if (type == null) return error(sender, "No vehicle type '" + args[1] + "'.");
                // cargo-box and winch-spot take an index/x y z, so they're handled before the single-value props.
                if (args[2].equalsIgnoreCase("cargo-box")) {
                    if (args.length < 7) return error(sender,
                        "/car edit " + type.id + " cargo-box <index> <x> <y> <z>   (index 0,1,2... = each box on the car)");
                    try {
                        int idx = Math.max(0, Integer.parseInt(args[3]));
                        double bx = Double.parseDouble(args[4]), by = Double.parseDouble(args[5]), bz = Double.parseDouble(args[6]);
                        while (type.cargoBoxes.size() <= idx) type.cargoBoxes.add(new double[]{0, 0, 0});
                        type.cargoBoxes.set(idx, new double[]{bx, by, bz});
                    } catch (NumberFormatException e) { return error(sender, "index and x y z must be numbers."); }
                    registry.save();
                    sender.sendMessage(Component.text(type.id + " cargo box #" + args[3] + " -> " + args[4] + " "
                        + args[5] + " " + args[6] + " (" + type.cargoBoxes.size() + " box(es); respawn cars to apply)",
                        NamedTextColor.AQUA));
                    return true;
                }
                if (args[2].equalsIgnoreCase("winch-spot")) {
                    if (args.length < 6) return error(sender,
                        "/car edit " + type.id + " winch-spot <x> <y> <z>   (also sets winch=true)");
                    try {
                        type.winchX = Double.parseDouble(args[3]);
                        type.winchY = Double.parseDouble(args[4]);
                        type.winchZ = Double.parseDouble(args[5]);
                        type.hasWinchSpot = true;
                    } catch (NumberFormatException e) { return error(sender, "x y z must be numbers."); }
                    registry.save();
                    sender.sendMessage(Component.text(type.id + " winch spot -> " + args[3] + " " + args[4] + " "
                        + args[5] + " (respawn cars to apply)", NamedTextColor.AQUA));
                    return true;
                }
                String value = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                try {
                    switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "name" -> type.name = value;
                        case "tag" -> type.tag = value;
                        case "max-speed" -> type.maxSpeed = Double.parseDouble(value);
                        case "acceleration" -> type.acceleration = Double.parseDouble(value);
                        case "turn-rate" -> type.turnRate = Double.parseDouble(value);
                        case "sound" -> type.sound = value;
                        case "seat-y-adjust" -> type.seatYAdjust = Double.parseDouble(value);
                        case "cargo-rows" -> type.cargoRows = Math.max(0, Math.min(6, Integer.parseInt(value)));
                        case "max-health" -> type.maxHealth = Math.max(1.0, Double.parseDouble(value));
                        case "cargo-box-scale" -> type.cargoBoxScale = Double.parseDouble(value);
                        case "cargo-box-clear" -> type.cargoBoxes.clear();
                        case "hitbox-width" -> type.hitboxWidth = Double.parseDouble(value);
                        case "hitbox-height" -> type.hitboxHeight = Double.parseDouble(value);
                        case "hitbox-offset-y" -> type.hitboxOffsetY = Double.parseDouble(value);
                        case "winch" -> type.hasWinchSpot = value.equalsIgnoreCase("true") || value.equals("1");
                        case "drift" -> type.drift = value.equalsIgnoreCase("true") || value.equals("1");
                        case "forklift" -> type.forklift = value.equalsIgnoreCase("true") || value.equals("1");
                        default -> { return error(sender,
                            "Properties: name, tag, max-speed, acceleration, turn-rate, sound, seat-y-adjust, cargo-rows, max-health, cargo-box <i> <x> <y> <z>, cargo-box-scale, cargo-box-clear, hitbox-width, hitbox-height, hitbox-offset-y, winch, winch-spot <x> <y> <z>, drift, forklift  (seats: /car seat <id> ...)"); }
                    }
                } catch (NumberFormatException e) {
                    return error(sender, "That property takes a number.");
                }
                registry.save();
                sender.sendMessage(Component.text(type.id + "." + args[2] + " = " + value
                    + " (respawn cars to apply)", NamedTextColor.AQUA));
                return true;
            }
            case "list" -> {
                for (CarType type : registry.all().values()) {
                    sender.sendMessage(Component.text(type.id + " - \"" + type.name + "\", "
                        + type.seatOffsets.size() + " seat(s), " + type.maxSpeed + " b/s, tag '" + type.tag + "'",
                        NamedTextColor.AQUA));
                }
                if (registry.all().isEmpty()) sender.sendMessage(
                    Component.text("No vehicle types. /car create <id> <tag>", NamedTextColor.GRAY));
                return true;
            }
            case "spawn" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 2) return usage(sender);
                CarType type = registry.get(args[1]);
                if (type == null) return error(sender, "No vehicle type '" + args[1] + "'.");
                spawnCar(type, player.getLocation());
                sender.sendMessage(Component.text(type.name + " delivered (following tag '" + type.tag
                    + "'). Spawn a tagged model within " + (int) MODEL_CLAIM_RADIUS
                    + " blocks if one isn't already there. Right-click to get in.", NamedTextColor.AQUA));
                return true;
            }
            case "winch" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                winch.toggle(player);   // take the winch from a nearby cargo vehicle (or stow it)
                return true;
            }
            case "reload" -> {
                registry.load();
                sender.sendMessage(Component.text("cars.yml reloaded ("
                    + registry.all().size() + " type(s)). Respawn cars to apply.", NamedTextColor.AQUA));
                return true;
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                boolean deleteModel = getConfig().getBoolean("delete-model-on-remove", false);
                int removed = 0;
                for (Entity entity : player.getLocation().getNearbyEntities(16, 16, 16)) {
                    var tags = entity.getScoreboardTags();
                    if (tags.contains(DriveTask.TAG_CAR) || tags.contains(DriveTask.TAG_PART)
                        || tags.contains(DriveTask.TAG_SEAT)) {
                        // the external model is the op's entity: left in place by default, deleted only when
                        // delete-model-on-remove is on.
                        if (entity instanceof Pig pig) {
                            Entity model = claimedModel(pig);
                            if (model != null && deleteModel) model.remove();
                        }
                        entity.getPassengers().forEach(p -> { if (!(p instanceof Player)) p.remove(); });
                        if (entity instanceof Pig || !(entity.getVehicle() instanceof Pig)) {
                            entity.remove();
                        }
                        removed++;
                    }
                }
                sender.sendMessage(Component.text("Scrapped " + removed + " car part(s) within 16 blocks"
                    + (deleteModel ? " (and their models)." : " (models left in place)."), NamedTextColor.AQUA));
                return true;
            }
            case "monorail", "rail" -> { return monorail(sender, args); }
            default -> { return usage(sender); }
        }
    }

    /** /car seat <id> driver|<n> <x> <y> <z> - set a seat offset (n one past the end adds a seat). */
    private boolean seatCommand(CommandSender sender, String[] args) {
        if (args.length < 6) return error(sender,
            "/car seat <id> driver <x> <y> <z>   |   /car seat <id> <n> <x> <y> <z>   (n = 1,2,...; one past the end adds a seat)");
        CarType type = registry.get(args[1]);
        if (type == null) return error(sender, "No vehicle type '" + args[1] + "'.");
        int index;
        if (args[2].equalsIgnoreCase("driver")) {
            index = 0;
        } else {
            try { index = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) { return error(sender, "Seat must be 'driver' or a number 1,2,..."); }
            if (index < 1) return error(sender, "Passenger seat number starts at 1 (0 = driver).");
        }
        double x, y, z;
        try { x = Double.parseDouble(args[3]); y = Double.parseDouble(args[4]); z = Double.parseDouble(args[5]); }
        catch (NumberFormatException e) { return error(sender, "x y z must be numbers."); }
        if (index > type.seatOffsets.size()) return error(sender,
            "Seat " + index + " skips a gap - the next addable seat is " + type.seatOffsets.size() + ".");
        if (index == type.seatOffsets.size()) type.seatOffsets.add(new double[]{x, y, z});
        else type.seatOffsets.set(index, new double[]{x, y, z});
        registry.save();
        sender.sendMessage(Component.text(type.id + " " + (index == 0 ? "driver" : "seat " + index) + " -> "
            + x + " " + y + " " + z + " (" + type.seatOffsets.size() + " seat(s); respawn cars to apply)",
            NamedTextColor.AQUA));
        return true;
    }

    /** /car monorail line <name> | node <name> | build <name> | cart <name> | list | remove <name> | scrap */
    private boolean monorail(CommandSender sender, String[] args) {
        if (args.length < 2) return error(sender,
            "/car monorail line <name> | node <name> | build <name> | cart <name> | list | remove <name> | scrap");
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "line" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 3) return error(sender, "/car monorail line <name>");
                var line = monorail.create(args[2], player.getWorld());
                sender.sendMessage(Component.text("Line '" + line.name + "' ready (" + line.nodes.size()
                    + " node(s)). Stand where you want track and run /car monorail node " + line.name,
                    NamedTextColor.AQUA));
                return true;
            }
            case "node" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 3) return error(sender, "/car monorail node <name>");
                int count = monorail.addNode(args[2], player.getLocation());
                if (count < 0) return error(sender, "No line '" + args[2] + "'. Make it with /car monorail line " + args[2]);
                sender.sendMessage(Component.text("Node " + count + " added to '" + args[2]
                    + "' at your feet. Add more, then /car monorail build " + args[2], NamedTextColor.AQUA));
                return true;
            }
            case "build" -> {
                if (args.length < 3) return error(sender, "/car monorail build <name>");
                int placed = monorail.buildTrack(args[2]);
                if (placed < 0) return error(sender, "No line '" + args[2] + "' (or its world isn't loaded).");
                sender.sendMessage(Component.text("Built '" + args[2] + "': " + placed
                    + " track piece(s). /car monorail cart " + args[2] + " to add a cart.", NamedTextColor.AQUA));
                return true;
            }
            case "cart" -> {
                if (args.length < 3) return error(sender, "/car monorail cart <name>");
                if (!monorail.spawnCart(args[2]))
                    return error(sender, "Line '" + args[2] + "' needs at least two nodes first.");
                sender.sendMessage(Component.text("Cart placed on '" + args[2]
                    + "'. Right-click it to ride.", NamedTextColor.AQUA));
                return true;
            }
            case "list" -> {
                if (monorail.all().isEmpty()) return error(sender, "No monorail lines. /car monorail line <name>");
                for (var line : monorail.all()) {
                    sender.sendMessage(Component.text(line.name + " - " + line.nodes.size()
                        + " node(s) in " + line.world, NamedTextColor.AQUA));
                }
                return true;
            }
            case "remove" -> {
                if (args.length < 3) return error(sender, "/car monorail remove <name>");
                if (!monorail.remove(args[2])) return error(sender, "No line '" + args[2] + "'.");
                sender.sendMessage(Component.text("Removed line '" + args[2] + "' and its track.", NamedTextColor.AQUA));
                return true;
            }
            case "scrap" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                int removed = 0;
                for (Entity entity : player.getLocation().getNearbyEntities(16, 16, 16)) {
                    var tags = entity.getScoreboardTags();
                    if (tags.contains(MonorailManager.TAG_MONO) || tags.contains(MonorailManager.TAG_MONO_PART)
                        || tags.contains(MonorailManager.TAG_MONO_SEAT)) {
                        entity.getPassengers().forEach(p -> { if (!(p instanceof Player)) p.remove(); });
                        if (entity instanceof Pig || !(entity.getVehicle() instanceof Pig)) entity.remove();
                        removed++;
                    }
                }
                sender.sendMessage(Component.text("Scrapped " + removed + " monorail cart part(s) within 16 blocks.",
                    NamedTextColor.AQUA));
                return true;
            }
            default -> { return error(sender,
                "/car monorail line <name> | node <name> | build <name> | cart <name> | list | remove <name> | scrap"); }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> filter(Stream.of("create", "edit", "seat", "seats", "list", "spawn", "winch", "remove", "reload", "monorail"), args[0]);
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "edit", "spawn", "seat", "seats" -> filter(registry.all().keySet().stream(), args[1]);
                case "monorail", "rail" -> filter(Stream.of("line", "node", "build", "cart", "list", "remove", "scrap"), args[1]);
                default -> List.of();
            };
            case 3 -> {
                if (args[0].equalsIgnoreCase("edit")) {
                    yield filter(Stream.of("name", "tag", "max-speed", "acceleration", "turn-rate",
                        "sound", "seat-y-adjust", "cargo-rows", "max-health", "cargo-box", "cargo-box-scale",
                        "cargo-box-clear", "hitbox-width", "hitbox-height", "hitbox-offset-y", "winch",
                        "winch-spot", "drift", "forklift"), args[2]);
                }
                if (args[0].equalsIgnoreCase("seat")) {
                    yield filter(Stream.of("driver", "1", "2", "3"), args[2]);
                }
                if ((args[0].equalsIgnoreCase("monorail") || args[0].equalsIgnoreCase("rail"))
                    && Stream.of("node", "build", "cart", "remove").anyMatch(s -> s.equalsIgnoreCase(args[1]))) {
                    yield filter(monorail.all().stream().map(l -> l.name), args[2]);
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList();
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/car create <id> <tag> | edit <id> <prop> <value> | seat <id> driver|<n> <x> <y> <z> | seats <id> | list | spawn <id> | remove | monorail ...",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
