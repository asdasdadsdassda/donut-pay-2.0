package com.example.donutpay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Settings live in .minecraft/config/donutpay.json. Most of them can be changed in the in-game GUI. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    /** Turn the buttons on/off. */
    public boolean enabled = true;
    /** Payments below this amount are ignored (no buttons). */
    public long minAmount = 1;
    /** Payments above this amount are ignored. 0 = no limit. */
    public long maxAmount = 0;
    /** What the Win button pays back (2 = double). */
    public double winMultiplier = 2.0;

    /** Matches "USERNAME Paid You AMOUNT". Group 1 = payer name, group 2 = amount (1,000 / 2.5K / 3M / 1B). Case-insensitive. */
    public static final String DEFAULT_PATTERN = "(?i)^([.\\w]{3,17})\\s+paid\\s+you\\s+\\$?([\\d,]*\\.?\\d+\\s?[KMBT]?)";
    public static final int CONFIG_VERSION = 2;

    /** Internal: lets the mod refresh the default pattern when it changes. */
    public int configVersion = CONFIG_VERSION;
    public String paymentPattern = DEFAULT_PATTERN;
    /** Command used to send money. Placeholders: {player} {amount} */
    public String payCommand = "pay {player} {amount}";

    public transient Pattern compiled;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutpay.json");
    }

    public static synchronized Config get() {
        if (instance == null) instance = load();
        return instance;
    }

    public synchronized void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (Exception e) {
            DonutPayClient.LOG.warn("Could not save donutpay.json", e);
        }
    }

    private static Config load() {
        Config cfg = new Config();
        try {
            if (Files.exists(file())) {
                Config read = GSON.fromJson(Files.readString(file()), Config.class);
                if (read != null) cfg = read;
            }
        } catch (Exception e) {
            DonutPayClient.LOG.warn("Could not read donutpay.json, using defaults", e);
        }
        if (cfg.configVersion < CONFIG_VERSION) { // old config -> use the updated default pattern
            cfg.paymentPattern = DEFAULT_PATTERN;
            cfg.configVersion = CONFIG_VERSION;
        }
        try {
            cfg.compiled = Pattern.compile(cfg.paymentPattern);
        } catch (Exception e) {
            DonutPayClient.LOG.error("Invalid paymentPattern, using default", e);
            cfg.compiled = Pattern.compile(DEFAULT_PATTERN);
        }
        cfg.save(); // writes defaults / fills in new fields
        return cfg;
    }
}
