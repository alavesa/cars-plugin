package fi.alavesa.cars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pig;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A custom-modelled monorail transit system, built on the same rideable rig as the cars
 * (an invisible Pig base carrying an ItemDisplay body + Interaction hitbox, moved by
 * velocity so passengers ride along). A LINE is a free-form ordered list of node positions
 * laid anywhere in the world; consecutive nodes form segments. buildTrack renders the custom
 * rail model along every segment; a CART auto-transits the line node to node (ping-ponging
 * between the two ends with a short dwell at each), carrying whoever is aboard.
 *
 * Lines persist to monorails.yml as lines.<name>.{world, nodes[]} where each node is "x,y,z".
 */
public final class MonorailManager {

    // Dedicated tags so the cars' DriveTask/CarListener never touch monorail entities.
    public static final String TAG_MONO = "cars.monorail";        // the Pig base
    public static final String TAG_MONO_PART = "cars.monorail.part"; // body + hitbox
    public static final String TAG_MONO_SEAT = "cars.monorail.seat"; // rider seat stand
    public static final String TAG_RAIL = "cars.monorail.rail";    // a track segment display

    public static final String CART_MODEL = "monorail_cart";
    public static final String RAIL_MODEL = "monorail_rail";

    /** One monorail line: a world plus an ordered list of node positions. */
    public static final class Line {
        final String name;
        String world;
        final List<Vector> nodes = new ArrayList<>();
        Line(String name, String world) { this.name = name; this.world = world; }
    }

    private final CarsPlugin plugin;
    private final File file;
    private final YamlConfiguration cfg;
    private final Map<String, Line> lines = new LinkedHashMap<>();

    final NamespacedKey lineKey;   // line name, on the base + on each rail display
    final NamespacedKey idxKey;    // target node index, on the base
    final NamespacedKey dirKey;    // travel direction (+1 / -1), on the base
    final NamespacedKey baseKey;   // base UUID, on the seat stand

    public MonorailManager(CarsPlugin plugin) {
        this.plugin = plugin;
        this.lineKey = new NamespacedKey(plugin, "mono_line");
        this.idxKey = new NamespacedKey(plugin, "mono_index");
        this.dirKey = new NamespacedKey(plugin, "mono_dir");
        this.baseKey = new NamespacedKey(plugin, "mono_base");
        this.file = new File(plugin.getDataFolder(), "monorails.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
        load();
    }

    // --- persistence --------------------------------------------------------

    public void load() {
        lines.clear();
        var sec = cfg.getConfigurationSection("lines");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            String p = "lines." + name + ".";
            Line line = new Line(name, cfg.getString(p + "world", "world"));
            for (String node : cfg.getStringList(p + "nodes")) {
                String[] c = node.split(",");
                if (c.length != 3) continue;
                try {
                    line.nodes.add(new Vector(Double.parseDouble(c[0]),
                        Double.parseDouble(c[1]), Double.parseDouble(c[2])));
                } catch (NumberFormatException ignored) { }
            }
            lines.put(name.toLowerCase(Locale.ROOT), line);
        }
    }

    private void save(Line line) {
        String p = "lines." + line.name + ".";
        cfg.set(p + "world", line.world);
        List<String> nodes = new ArrayList<>();
        for (Vector v : line.nodes) nodes.add(v.getX() + "," + v.getY() + "," + v.getZ());
        cfg.set(p + "nodes", nodes);
        try { cfg.save(file); } catch (IOException ignored) { }
    }

    // --- line editing -------------------------------------------------------

    public Line line(String name) { return lines.get(name.toLowerCase(Locale.ROOT)); }
    public java.util.Collection<Line> all() { return lines.values(); }

    /** Create a line (or return the existing one). Name keeps the operator's capitals. */
    public Line create(String name, World world) {
        Line existing = lines.get(name.toLowerCase(Locale.ROOT));
        if (existing != null) return existing;
        Line line = new Line(name, world.getName());
        lines.put(name.toLowerCase(Locale.ROOT), line);
        save(line);
        return line;
    }

    /** Append a node (a world position) to a line. Returns the new node count, or -1 if no line. */
    public int addNode(String name, Location at) {
        Line line = lines.get(name.toLowerCase(Locale.ROOT));
        if (line == null) return -1;
        line.world = at.getWorld().getName();
        line.nodes.add(at.toVector());
        save(line);
        return line.nodes.size();
    }

    public boolean remove(String name) {
        Line line = lines.remove(name.toLowerCase(Locale.ROOT));
        if (line == null) return false;
        clearTrack(name);
        cfg.set("lines." + line.name, null);
        try { cfg.save(file); } catch (IOException ignored) { }
        return true;
    }

    // --- track rendering ----------------------------------------------------

    /** (Re)build the visible track for a line. Returns pieces placed, or -1 if the line/world is gone. */
    public int buildTrack(String name) {
        Line line = lines.get(name.toLowerCase(Locale.ROOT));
        if (line == null) return -1;
        World w = Bukkit.getWorld(line.world);
        if (w == null) return -1;
        clearTrack(name);
        double spacing = Math.max(0.25, plugin.getConfig().getDouble("monorail.rail-spacing", 1.0));
        int cap = plugin.getConfig().getInt("monorail.max-rail-pieces", 4000);
        int placed = 0;
        for (int i = 0; i + 1 < line.nodes.size(); i++) {
            Vector a = line.nodes.get(i), b = line.nodes.get(i + 1);
            Vector seg = b.clone().subtract(a);
            double len = seg.length();
            if (len < 1e-6) continue;
            float[] rot = orient(seg);
            Vector step = seg.clone().normalize().multiply(spacing);
            int count = (int) Math.floor(len / spacing);
            for (int k = 0; k <= count; k++) {
                Vector pos = a.clone().add(step.clone().multiply(k));
                spawnRail(new Location(w, pos.getX(), pos.getY(), pos.getZ()), line.name, rot[0], rot[1]);
                if (++placed >= cap) return placed;
            }
        }
        return placed;
    }

    /** Remove all rail displays belonging to a line. */
    public void clearTrack(String name) {
        World w = null;
        Line line = lines.get(name.toLowerCase(Locale.ROOT));
        if (line != null) w = Bukkit.getWorld(line.world);
        for (World world : (w != null ? List.of(w) : Bukkit.getWorlds())) {
            for (ItemDisplay d : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!d.getScoreboardTags().contains(TAG_RAIL)) continue;
                String owner = d.getPersistentDataContainer().get(lineKey, PersistentDataType.STRING);
                if (owner != null && owner.equalsIgnoreCase(name)) d.remove();
            }
        }
    }

    private void spawnRail(Location at, String lineName, float yaw, float pitch) {
        at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.setRotation(yaw, pitch);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(8, 15));
            d.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1f, 1f, 1f), new AxisAngle4f(0, 0, 0, 1)));
            d.addScoreboardTag(TAG_RAIL);
            d.getPersistentDataContainer().set(lineKey, PersistentDataType.STRING, lineName);
            d.setItemStack(modelItem(RAIL_MODEL));
        });
    }

    // --- carts --------------------------------------------------------------

    /** Spawn a cart at the line's first node, ready to transit toward node 1. Returns false if
     *  the line has fewer than two nodes (nothing to travel along). */
    public boolean spawnCart(String name) {
        Line line = lines.get(name.toLowerCase(Locale.ROOT));
        if (line == null || line.nodes.size() < 2) return false;
        World w = Bukkit.getWorld(line.world);
        if (w == null) return false;
        Vector n0 = line.nodes.get(0);
        Location loc = new Location(w, n0.getX(), n0.getY(), n0.getZ());
        float[] rot = orient(line.nodes.get(1).clone().subtract(n0));

        Pig base = w.spawn(loc, Pig.class, pig -> {
            pig.setInvisible(true);
            pig.setSilent(true);
            pig.setPersistent(true);
            pig.setRemoveWhenFarAway(false);
            pig.setAdult();
            pig.setGravity(false);          // the monorail holds its rail height, never falls
            pig.setCustomNameVisible(false);
            pig.customName(Component.text("Monorail: " + line.name, NamedTextColor.GRAY));
            pig.addScoreboardTag(TAG_MONO);
            var pdc = pig.getPersistentDataContainer();
            pdc.set(lineKey, PersistentDataType.STRING, line.name);
            pdc.set(idxKey, PersistentDataType.INTEGER, 1);   // heading toward node 1
            pdc.set(dirKey, PersistentDataType.INTEGER, 1);
        });
        // AI on but unaware (NoAI freezes velocity integration; unaware stops all wandering).
        base.setAware(false);
        Bukkit.getMobGoals().removeAllGoals(base);

        ItemDisplay body = w.spawn(loc, ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setShadowRadius(0.9f);
            d.setShadowStrength(0.7f);
            d.setRotation(rot[0], rot[1]);
            d.setTransformation(new Transformation(
                new Vector3f(0f, 0.3f, 0f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1.7f, 1.7f, 1.7f), new AxisAngle4f(0, 0, 0, 1)));
            d.addScoreboardTag(TAG_MONO_PART);
            d.setItemStack(modelItem(CART_MODEL));
        });
        base.addPassenger(body);

        Interaction hitbox = w.spawn(loc, Interaction.class, i -> {
            i.setInteractionWidth(2.4f);
            i.setInteractionHeight(1.8f);
            i.setPersistent(true);
            i.addScoreboardTag(TAG_MONO_PART);
        });
        base.addPassenger(hitbox);

        w.spawn(loc.clone().add(0, 0.1, 0), ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setGravity(false);
            s.setPersistent(true);
            s.setSmall(true);
            s.setInvulnerable(true);
            s.setMarker(false);   // a marker stand can't carry a passenger
            s.addScoreboardTag(TAG_MONO_SEAT);
            s.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING,
                base.getUniqueId().toString());
        });
        return true;
    }

    // --- helpers ------------------------------------------------------------

    ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.MINECART);
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** Yaw + pitch (degrees) that make a display's +Z face along the given direction. */
    static float[] orient(Vector dir) {
        double h = Math.hypot(dir.getX(), dir.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(dir.getY(), h));
        return new float[]{yaw, pitch};
    }
}
