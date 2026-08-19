package fi.alavesa.cars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
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

    @Override
    public void onEnable() {
        typeKey = new NamespacedKey(this, "type");
        carKey = new NamespacedKey(this, "car");
        seatKey = new NamespacedKey(this, "seat");
        healthKey = new NamespacedKey(this, "health");
        cargoKey = new NamespacedKey(this, "cargo");
        wreckedKey = new NamespacedKey(this, "wrecked");
        attackKey = new NamespacedKey(this, "last_attack");
        getConfig().addDefault("seat-y-adjust", -0.72);
        getConfig().addDefault("monorail.speed", 0.3);
        getConfig().addDefault("monorail.arrive", 0.7);
        getConfig().addDefault("monorail.dwell-ticks", 40);
        getConfig().addDefault("monorail.seat-y", 0.4);
        getConfig().addDefault("monorail.rail-spacing", 1.0);
        getConfig().addDefault("monorail.max-rail-pieces", 4000);
        getConfig().options().copyDefaults(true);
        saveConfig();
        java.io.File bundled = new java.io.File(getDataFolder(), "models/car_jeep.json");
        if (!bundled.isFile()) saveResource("models/car_jeep.json", false);
        registry = new CarRegistry(this);
        registry.load();
        task = new DriveTask(this);
        monorail = new MonorailManager(this);
        monoTask = new MonorailTask(this, monorail);
        getServer().getPluginManager().registerEvents(new CarListener(this, task), this);
        getServer().getPluginManager().registerEvents(new MonorailListener(this, monorail), this);
        getServer().getScheduler().runTaskTimer(this, task, 20L, 1L);
        getServer().getScheduler().runTaskTimer(this, monoTask, 20L, 1L);
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
        ItemDisplay body = location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setPersistent(true);
            display.setTeleportDuration(1);
            // no brightness override: the body takes the light of wherever it
            // is - dark in a dark corridor, bright in the sun - plus a soft
            // ground shadow to sit it in the scene
            display.setShadowRadius(1.15f);
            display.setShadowStrength(0.9f);
            display.setTransformation(new Transformation(
                new Vector3f((float) type.offsetX, (float) type.offsetY, (float) type.offsetZ),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f((float) type.scale, (float) type.scale, (float) type.scale),
                new AxisAngle4f(0, 0, 0, 1)));
            display.addScoreboardTag(DriveTask.TAG_PART);
            ItemStack item = new ItemStack(Material.MINECART);
            ItemMeta meta = item.getItemMeta();
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setStrings(List.of(type.model));
            meta.setCustomModelDataComponent(component);
            item.setItemMeta(meta);
            display.setItemStack(item);
        });
        base.addPassenger(body);
        Interaction hitbox = location.getWorld().spawn(location, Interaction.class, i -> {
            i.setInteractionWidth(1.9f);
            i.setInteractionHeight(1.5f);
            i.setPersistent(true);
            i.addScoreboardTag(DriveTask.TAG_PART);
        });
        base.addPassenger(hitbox);
        int seatCount = Math.max(type.seats, type.seatOffsets.isEmpty() ? 0 : type.seatOffsets.size());
        for (int seat = 0; seat < seatCount; seat++) {
            spawnSeat(base, seat);
        }
        spawnCargoBoxes(base, type);
    }

    /** Spawn one visible cargo box (barrel / crate) per configured position, riding the car. */
    public void spawnCargoBoxes(Pig base, CarType type) {
        for (double[] box : type.cargoBoxes) {
            org.bukkit.entity.Display disp;
            Transformation xf = new Transformation(
                new Vector3f((float) box[0], (float) box[1], (float) box[2]),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f((float) type.cargoBoxScale, (float) type.cargoBoxScale, (float) type.cargoBoxScale),
                new AxisAngle4f(0, 0, 0, 1));
            if (type.cargoBoxModel == null || type.cargoBoxModel.isEmpty()) {
                disp = base.getWorld().spawn(base.getLocation(), org.bukkit.entity.BlockDisplay.class, d -> {
                    d.setBlock(Material.BARREL.createBlockData());
                    d.setPersistent(true); d.setTeleportDuration(1); d.setTransformation(xf);
                    d.addScoreboardTag(DriveTask.TAG_PART); d.addScoreboardTag(TAG_CARGOBOX);
                });
            } else {
                disp = base.getWorld().spawn(base.getLocation(), ItemDisplay.class, d -> {
                    ItemStack it = new ItemStack(Material.BARREL);
                    ItemMeta m = it.getItemMeta();
                    CustomModelDataComponent c = m.getCustomModelDataComponent();
                    c.setStrings(List.of(type.cargoBoxModel));
                    m.setCustomModelDataComponent(c);
                    it.setItemMeta(m);
                    d.setItemStack(it);
                    d.setPersistent(true); d.setTeleportDuration(1); d.setTransformation(xf);
                    d.addScoreboardTag(DriveTask.TAG_PART); d.addScoreboardTag(TAG_CARGOBOX);
                });
            }
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

    public ItemDisplay bodyOf(Pig base) {
        for (Entity p : base.getPassengers()) {
            if (p instanceof ItemDisplay d && d.getScoreboardTags().contains(DriveTask.TAG_PART)) return d;
        }
        return null;
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

    /** Turn a car into an inert wreck: same model, swapped to the wreck texture, undrivable, no seats. */
    public void wreckCar(Pig base) {
        if (isWrecked(base)) return;
        CarType type = typeOf(base);
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
        // swap the body model to the wreck variant
        ItemDisplay body = bodyOf(base);
        if (body != null && type != null) {
            ItemStack item = new ItemStack(Material.MINECART);
            ItemMeta meta = item.getItemMeta();
            CustomModelDataComponent c = meta.getCustomModelDataComponent();
            c.setStrings(List.of(type.wreckModel));
            meta.setCustomModelDataComponent(c);
            item.setItemMeta(meta);
            body.setItemStack(item);
        }
        base.getWorld().playSound(base.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
        base.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, base.getLocation().add(0, 1, 0), 30, 0.8, 0.6, 0.8, 0.05);
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
                if (args.length < 2) return usage(sender);
                String id = args[1].toLowerCase(Locale.ROOT);
                if (registry.get(id) != null) return error(sender, "'" + id + "' already exists.");
                CarType type = registry.create(id);
                sender.sendMessage(Component.text("Created vehicle type '" + id
                    + "' (model hook: " + type.model + "). Tune it with /car edit " + id + " ...",
                    NamedTextColor.AQUA));
                return true;
            }
            case "edit" -> {
                if (args.length < 4) return usage(sender);
                CarType type = registry.get(args[1]);
                if (type == null) return error(sender, "No vehicle type '" + args[1] + "'.");
                // cargo-box takes an index + x y z, so it's handled before the single-value properties.
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
                String value = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                try {
                    switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "name" -> type.name = value;
                        case "model" -> type.model = value;
                        case "max-speed" -> type.maxSpeed = Double.parseDouble(value);
                        case "acceleration" -> type.acceleration = Double.parseDouble(value);
                        case "turn-rate" -> type.turnRate = Double.parseDouble(value);
                        case "scale" -> type.scale = Double.parseDouble(value);
                        case "sound" -> type.sound = value;
                        case "seats" -> type.seats = Math.max(1, Math.min(4, Integer.parseInt(value)));
                        case "offset-x" -> type.offsetX = Double.parseDouble(value);
                        case "offset-y" -> type.offsetY = Double.parseDouble(value);
                        case "offset-z" -> type.offsetZ = Double.parseDouble(value);
                        case "seat-y-adjust" -> type.seatYAdjust = Double.parseDouble(value);
                        case "cargo-rows" -> type.cargoRows = Math.max(0, Math.min(6, Integer.parseInt(value)));
                        case "max-health" -> type.maxHealth = Math.max(1.0, Double.parseDouble(value));
                        case "wreck-model" -> type.wreckModel = value;
                        case "cargo-box-model" -> type.cargoBoxModel = value.equalsIgnoreCase("none") ? "" : value;
                        case "cargo-box-scale" -> type.cargoBoxScale = Double.parseDouble(value);
                        case "cargo-box-clear" -> type.cargoBoxes.clear();
                        case "drift" -> type.drift = value.equalsIgnoreCase("true") || value.equals("1");
                        default -> { return error(sender,
                            "Properties: name, model, max-speed, acceleration, turn-rate, scale, sound, seats, offset-x/y/z, seat-y-adjust, cargo-rows, max-health, wreck-model, cargo-box <i> <x> <y> <z>, cargo-box-model, cargo-box-scale, cargo-box-clear"); }
                    }
                } catch (NumberFormatException e) {
                    return error(sender, "That property takes a number.");
                }
                registry.save();
                sender.sendMessage(Component.text(type.id + "." + args[2] + " = " + value
                    + " (existing cars keep their old seats until respawned)", NamedTextColor.AQUA));
                return true;
            }
            case "list" -> {
                for (CarType type : registry.all().values()) {
                    sender.sendMessage(Component.text(type.id + " - \"" + type.name + "\", "
                        + type.seats + " seat(s), " + type.maxSpeed + " b/s, model " + type.model,
                        NamedTextColor.AQUA));
                }
                if (registry.all().isEmpty()) sender.sendMessage(
                    Component.text("No vehicle types. /car create <id>", NamedTextColor.GRAY));
                return true;
            }
            case "spawn" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 2) return usage(sender);
                CarType type = registry.get(args[1]);
                if (type == null) return error(sender, "No vehicle type '" + args[1] + "'.");
                spawnCar(type, player.getLocation());
                sender.sendMessage(Component.text(type.name + " delivered. Right-click to get in.",
                    NamedTextColor.AQUA));
                return true;
            }
            case "reload" -> {
                registry.load();
                sender.sendMessage(Component.text("cars.yml and seat models reloaded ("
                    + registry.all().size() + " type(s)). Respawn cars to apply.", NamedTextColor.AQUA));
                return true;
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                int removed = 0;
                for (Entity entity : player.getLocation().getNearbyEntities(16, 16, 16)) {
                    var tags = entity.getScoreboardTags();
                    if (tags.contains(DriveTask.TAG_CAR) || tags.contains(DriveTask.TAG_PART)
                        || tags.contains(DriveTask.TAG_SEAT)) {
                        entity.getPassengers().forEach(p -> { if (!(p instanceof Player)) p.remove(); });
                        if (entity instanceof Pig || !(entity.getVehicle() instanceof Pig)) {
                            entity.remove();
                        }
                        removed++;
                    }
                }
                sender.sendMessage(Component.text("Scrapped " + removed + " car part(s) within 16 blocks.",
                    NamedTextColor.AQUA));
                return true;
            }
            case "monorail", "rail" -> { return monorail(sender, args); }
            default -> { return usage(sender); }
        }
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
            case 1 -> filter(Stream.of("create", "edit", "list", "spawn", "remove", "reload", "monorail"), args[0]);
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "edit", "spawn" -> filter(registry.all().keySet().stream(), args[1]);
                case "monorail", "rail" -> filter(Stream.of("line", "node", "build", "cart", "list", "remove", "scrap"), args[1]);
                default -> List.of();
            };
            case 3 -> {
                if (args[0].equalsIgnoreCase("edit")) {
                    yield filter(Stream.of("name", "model", "max-speed", "acceleration", "turn-rate",
                        "scale", "sound", "seats", "offset-x", "offset-y", "offset-z", "seat-y-adjust",
                        "cargo-rows", "max-health", "wreck-model", "cargo-box", "cargo-box-model",
                        "cargo-box-scale", "cargo-box-clear", "drift"), args[2]);
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
            "/car create <id> | edit <id> <prop> <value> | list | spawn <id> | remove | monorail ...",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
