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

    @Override
    public void onEnable() {
        typeKey = new NamespacedKey(this, "type");
        carKey = new NamespacedKey(this, "car");
        seatKey = new NamespacedKey(this, "seat");
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
                        default -> { return error(sender,
                            "Properties: name, model, max-speed, acceleration, turn-rate, scale, sound, seats, offset-x/y/z, seat-y-adjust"); }
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
                        "scale", "sound", "seats", "offset-x", "offset-y", "offset-z", "seat-y-adjust"), args[2]);
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
