package fi.alavesa.cars;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** cars.yml in, cars.yml out - all vehicle types, editable at runtime. */
public final class CarRegistry {

    private final CarsPlugin plugin;
    private final File file;
    private final Map<String, CarType> types = new LinkedHashMap<>();

    public CarRegistry(CarsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cars.yml");
    }

    public void load() {
        types.clear();
        if (!file.isFile()) {
            CarType jeep = new CarType("jeep");
            jeep.name = "Facility Jeep";
            jeep.tag = "jeep";
            jeep.seatOffsets = new java.util.ArrayList<>(java.util.List.of(
                new double[]{0.35, 1.0, 0.3}, new double[]{-0.35, 1.0, 0.3},
                new double[]{0.35, 1.0, -0.6}, new double[]{-0.35, 1.0, -0.6}));
            types.put(jeep.id, jeep);
            CarType forklift = new CarType("forklift");   // built-in cargo hauler that auto-loads barrels
            forklift.name = "Forklift";
            forklift.tag = "forklift";
            forklift.maxSpeed = 5.0;
            forklift.cargoRows = 3;      // a small-items hold too
            forklift.forklift = true;    // drives up to a barrel and reels it aboard on its own
            forklift.seatOffsets = new java.util.ArrayList<>(java.util.List.of(new double[]{0.0, 1.0, 0.0}));
            types.put(forklift.id, forklift);
            save();
        } else {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String id : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(id);
                if (section != null) types.put(id, CarType.load(id, section));
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (CarType type : types.values()) {
            type.save(yaml.createSection(type.id));
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save cars.yml: " + e.getMessage());
        }
    }

    public CarType get(String id) { return types.get(id.toLowerCase()); }

    public CarType create(String id) {
        CarType type = new CarType(id.toLowerCase());
        types.put(type.id, type);
        save();
        return type;
    }

    public Map<String, CarType> all() { return types; }
}
