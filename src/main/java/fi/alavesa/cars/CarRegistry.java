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
            save();
        } else {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String id : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(id);
                if (section != null) types.put(id, CarType.load(id, section));
            }
        }
        for (CarType type : types.values()) {
            loadSeatsFromModel(type);
        }
    }

    /**
     * Seats straight from the artist's model: drop the same model json into
     * plugins/Cars/models/<model>.json, name one element "driverseat" and the
     * rest "seat", "seat2"... - their centers become the riding positions.
     * Model space: 16 units = 1 block, origin at (8,8,8), forward = +Z.
     */
    private void loadSeatsFromModel(CarType type) {
        File modelFile = new File(plugin.getDataFolder(), "models/" + type.model + ".json");
        if (!modelFile.isFile()) {
            type.seatOffsets = List.of();
            return;
        }
        try (FileReader reader = new FileReader(modelFile)) {
            JsonObject model = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray elements = model.getAsJsonArray("elements");
            if (elements == null) return;
            double[] driver = null;
            List<double[]> passengers = new ArrayList<>();
            for (JsonElement raw : elements) {
                JsonObject element = raw.getAsJsonObject();
                if (!element.has("name")) continue;
                String name = element.get("name").getAsString().toLowerCase();
                if (!name.startsWith("seat") && !name.equals("driverseat")) continue;
                JsonArray from = element.getAsJsonArray("from");
                JsonArray to = element.getAsJsonArray("to");
                double[] center = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    center[axis] = ((from.get(axis).getAsDouble() + to.get(axis).getAsDouble()) / 2.0 - 8) / 16.0;
                }
                if (name.equals("driverseat")) driver = center;
                else passengers.add(center);
            }
            passengers.sort((a, b) -> 0); // keep model order
            List<double[]> seats = new ArrayList<>();
            if (driver != null) seats.add(driver);
            seats.addAll(passengers);
            type.seatOffsets = seats;
            if (!seats.isEmpty()) {
                plugin.getLogger().info(type.id + ": " + seats.size()
                    + " seat(s) read from models/" + type.model + ".json"
                    + (driver == null ? " (no 'driverseat' element - first seat drives)" : ""));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse models/" + type.model + ".json: " + e.getMessage());
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
