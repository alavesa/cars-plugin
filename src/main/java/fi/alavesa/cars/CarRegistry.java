package fi.alavesa.cars;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            jeep.seats = 4;
            types.put(jeep.id, jeep);
            CarType forklift = new CarType("forklift");   // built-in cargo hauler that auto-loads barrels
            forklift.name = "Forklift";
            forklift.seats = 1;
            forklift.maxSpeed = 5.0;
            forklift.cargoRows = 3;      // a small-items hold too
            forklift.forklift = true;    // drives up to a barrel and reels it aboard on its own
            types.put(forklift.id, forklift);
            save();
        } else {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String id : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(id);
                if (section != null) types.put(id, CarType.load(id, section));
            }
        }
        autoRegisterFromModels();
        for (CarType type : types.values()) {
            loadSeatsFromModel(type);
        }
    }

    /** Any .bbmodel (or exported .json) dropped in plugins/Cars/models/ becomes a car type automatically -
     *  id = the file name. Existing configured types keep their settings; only NEW files are added. */
    private void autoRegisterFromModels() {
        File dir = new File(plugin.getDataFolder(), "models");
        File[] files = dir.listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".bbmodel") || l.endsWith(".json");
        });
        if (files == null) return;
        for (File f : files) {
            String id = f.getName().replaceFirst("(?i)\\.(bbmodel|json)$", "").toLowerCase();
            if (id.startsWith("car_")) id = id.substring(4);   // legacy car_jeep.json -> "jeep"
            if (types.containsKey(id)) continue;               // already configured
            CarType type = new CarType(id);
            type.name = Character.toUpperCase(id.charAt(0)) + id.substring(1);
            type.model = f.getName().replaceFirst("(?i)\\.(bbmodel|json)$", "");
            types.put(id, type);
            plugin.getLogger().info("Auto-registered car '" + id + "' from models/" + f.getName());
        }
        save();
    }

    /**
     * Seats straight from the artist's model: drop the same model json into
     * plugins/Cars/models/<model>.json, name one element "driverseat" and the
     * rest "seat", "seat2"... - their centers become the riding positions.
     * Model space: 16 units = 1 block, origin at (8,8,8), forward = +Z.
     */
    /** Find the model source for a type - a .bbmodel is preferred, else the exported .json. */
    private File modelFileFor(String model) {
        File bb = new File(plugin.getDataFolder(), "models/" + model + ".bbmodel");
        if (bb.isFile()) return bb;
        File json = new File(plugin.getDataFolder(), "models/" + model + ".json");
        return json.isFile() ? json : null;
    }

    /** Parse named cubes out of the model (.bbmodel or exported .json): seats (driverseat/seat*), the click
     *  HITBOX, and the WINCHHITBOX (where right-clicking takes out the winch). .bbmodel and Minecraft model
     *  JSON both carry an "elements" array of cubes with name/from/to in the same 16-unit space. */
    private void loadSeatsFromModel(CarType type) {
        File modelFile = modelFileFor(type.model);
        if (modelFile == null) { type.seatOffsets = List.of(); return; }
        try (FileReader reader = new FileReader(modelFile)) {
            JsonObject model = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray elements = model.getAsJsonArray("elements");
            if (elements == null) { type.seatOffsets = List.of(); return; }
            double[] driver = null;
            List<double[]> passengers = new ArrayList<>();
            for (JsonElement raw : elements) {
                JsonObject element = raw.getAsJsonObject();
                if (!element.has("name") || !element.has("from") || !element.has("to")) continue;
                String name = element.get("name").getAsString().toLowerCase();
                JsonArray from = element.getAsJsonArray("from");
                JsonArray to = element.getAsJsonArray("to");
                double[] center = new double[3];
                for (int axis = 0; axis < 3; axis++)
                    center[axis] = ((from.get(axis).getAsDouble() + to.get(axis).getAsDouble()) / 2.0 - 8) / 16.0;
                if (name.equals("driverseat")) driver = center;
                else if (name.startsWith("seat")) passengers.add(center);
                else if (name.equals("hitbox")) {
                    type.hitboxWidth = Math.abs(to.get(0).getAsDouble() - from.get(0).getAsDouble()) / 16.0;
                    type.hitboxHeight = Math.abs(to.get(1).getAsDouble() - from.get(1).getAsDouble()) / 16.0;
                    type.hitboxOffsetY = center[1];
                } else if (name.equals("winchhitbox")) {
                    type.hasWinchSpot = true;
                    type.winchX = center[0]; type.winchY = center[1]; type.winchZ = center[2];
                }
            }
            List<double[]> seats = new ArrayList<>();
            if (driver != null) seats.add(driver);
            seats.addAll(passengers);
            type.seatOffsets = seats;
            plugin.getLogger().info(type.id + ": read from models/" + modelFile.getName()
                + " - " + seats.size() + " seat(s)"
                + (type.hasWinchSpot ? ", winch spot" : "")
                + String.format(", hitbox %.2fx%.2f", type.hitboxWidth, type.hitboxHeight));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse models/" + type.model + ": " + e.getMessage());
            type.seatOffsets = List.of();
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
