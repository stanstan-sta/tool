package com.mindcraft.companion;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompanionConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-companion");
    private static CompanionConfig instance;

    public boolean enabled = true;
    public int broadcastIntervalMs = 1500;
    public boolean sendRoster = true;
    public boolean sendEvents = true;
    public boolean sendFacts = true;
    public List<String> playerAllowlist = Collections.emptyList();

    private CompanionConfig() {}

    public static CompanionConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static CompanionConfig load(Path configDir) {
        Path file = configDir.resolve("mindcraft-companion.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                instance = gson.fromJson(reader, CompanionConfig.class);
                if (instance == null) {
                    instance = new CompanionConfig();
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load companion config, using defaults", e);
                instance = new CompanionConfig();
            }
        } else {
            instance = new CompanionConfig();
            try {
                Files.createDirectories(configDir);
                try (Writer writer = Files.newBufferedWriter(file)) {
                    gson.toJson(instance, writer);
                }
                LOGGER.info("Created default companion config at {}", file);
            } catch (IOException e) {
                LOGGER.error("Failed to write default companion config", e);
            }
        }
        return instance;
    }

    private static CompanionConfig load() {
        return load(Path.of("config"));
    }
}
