package dev.hycompanion.speechbubbles.config;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the Speech Bubbles plugin.
 * 
 * Loads settings from config.yml in the plugin data folder.
 */
public class SpeechBubbleConfig {
    
    // Default values
    private static final long DEFAULT_DURATION = 5000;
    private static final int DEFAULT_MAX_WIDTH = 626;   // Original bubble image width
    private static final int DEFAULT_MAX_HEIGHT = 349;  // Original bubble image height
    private static final String DEFAULT_TEXT_COLOR = "#FFFFFF";
    private static final float DEFAULT_BACKGROUND_OPACITY = 0.9f;
    private static final int DEFAULT_MAX_BUBBLES_PER_PLAYER = 10;
    private static final int DEFAULT_CLEANUP_INTERVAL = 30;
    private static final float DEFAULT_FOV = 75.0f;     // Default field of view in degrees

    private final long defaultDuration;
    private final int defaultMaxWidth;
    private final int defaultMaxHeight;
    private final String defaultTextColor;
    private final float defaultBackgroundOpacity;
    private final int maxBubblesPerPlayer;
    private final int cleanupInterval;
    private final float fov;

    private SpeechBubbleConfig(Builder builder) {
        this.defaultDuration = builder.defaultDuration;
        this.defaultMaxWidth = builder.defaultMaxWidth;
        this.defaultMaxHeight = builder.defaultMaxHeight;
        this.defaultTextColor = builder.defaultTextColor;
        this.defaultBackgroundOpacity = builder.defaultBackgroundOpacity;
        this.maxBubblesPerPlayer = builder.maxBubblesPerPlayer;
        this.cleanupInterval = builder.cleanupInterval;
        this.fov = builder.fov;
    }

    // ========== Getters ==========

    public long getDefaultDuration() {
        return defaultDuration;
    }

    public int getDefaultMaxWidth() {
        return defaultMaxWidth;
    }

    public int getDefaultMaxHeight() {
        return defaultMaxHeight;
    }

    public String getDefaultTextColor() {
        return defaultTextColor;
    }

    public float getDefaultBackgroundOpacity() {
        return defaultBackgroundOpacity;
    }

    public int getMaxBubblesPerPlayer() {
        return maxBubblesPerPlayer;
    }

    public int getCleanupInterval() {
        return cleanupInterval;
    }

    public float getFov() {
        return fov;
    }

    // ========== Loading ==========

    /**
     * Load configuration from a YAML file.
     * 
     * @param configPath Path to the config file
     * @return Loaded configuration
     * @throws IOException if the file cannot be read
     */
    @Nonnull
    public static SpeechBubbleConfig load(@Nonnull Path configPath) throws IOException {
        Builder builder = new Builder();
        
        if (!Files.exists(configPath)) {
            // Return defaults
            System.out.println("[SpeechBubbles] Config file not found, using defaults: maxWidth=" 
                + DEFAULT_MAX_WIDTH + ", maxHeight=" + DEFAULT_MAX_HEIGHT
                + ", fov=" + DEFAULT_FOV);
            return builder.build();
        }
        
        System.out.println("[SpeechBubbles] Loading config from: " + configPath);
        String content = Files.readString(configPath);
        System.out.println("[SpeechBubbles] Config content:\n" + content);
        
        // Simple YAML parsing for our specific format
        parseYaml(content, builder);
        
        SpeechBubbleConfig config = builder.build();
        System.out.println("[SpeechBubbles] Config loaded: maxWidth=" + config.getDefaultMaxWidth() 
            + ", maxHeight=" + config.getDefaultMaxHeight()
            + ", fov=" + config.getFov());
        
        // Warn if using old default values
        if (config.getDefaultMaxWidth() == 250 || config.getDefaultMaxHeight() == 150) {
            System.err.println("[SpeechBubbles] WARNING: Config has old default values (250x150). "
                + "Consider updating to new defaults (626x349) or delete config file to regenerate.");
        }
        
        return config;
    }

    /**
     * Simple YAML parser for our config format.
     */
    private static void parseYaml(String content, Builder builder) {
        String[] lines = content.split("\n");
        String currentSection = "";
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            
            // Check for section headers
            if (trimmed.endsWith(":")) {
                currentSection = trimmed.substring(0, trimmed.length() - 1);
                continue;
            }
            
            // Parse key-value pairs
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex > 0) {
                String key = trimmed.substring(0, colonIndex).trim();
                String value = trimmed.substring(colonIndex + 1).trim();
                
                // Remove quotes if present
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                
                // Remove inline comments
                int commentIndex = value.indexOf(" #");
                if (commentIndex > 0) {
                    value = value.substring(0, commentIndex).trim();
                }
                
                parseValue(currentSection, key, value, builder);
            }
        }
    }

    private static void parseValue(String section, String key, String value, Builder builder) {
        try {
            if ("defaults".equals(section)) {
                switch (key) {
                    case "duration":
                        builder.defaultDuration = Long.parseLong(value);
                        break;
                    case "maxWidth":
                        builder.defaultMaxWidth = Integer.parseInt(value);
                        break;
                    case "maxHeight":
                        builder.defaultMaxHeight = Integer.parseInt(value);
                        break;
                    case "textColor":
                        builder.defaultTextColor = value;
                        break;
                    case "backgroundOpacity":
                        builder.defaultBackgroundOpacity = Float.parseFloat(value);
                        break;
                    case "fov":
                        builder.fov = Float.parseFloat(value);
                        break;
                }
            } else if (section.isEmpty()) {
                switch (key) {
                    case "maxBubblesPerPlayer":
                        builder.maxBubblesPerPlayer = Integer.parseInt(value);
                        break;
                    case "cleanupInterval":
                        builder.cleanupInterval = Integer.parseInt(value);
                        break;
                }
            }
        } catch (NumberFormatException e) {
            // Ignore invalid values, use defaults
        }
    }

    // ========== Builder ==========

    private static class Builder {
        private long defaultDuration = DEFAULT_DURATION;
        private int defaultMaxWidth = DEFAULT_MAX_WIDTH;
        private int defaultMaxHeight = DEFAULT_MAX_HEIGHT;
        private String defaultTextColor = DEFAULT_TEXT_COLOR;
        private float defaultBackgroundOpacity = DEFAULT_BACKGROUND_OPACITY;
        private int maxBubblesPerPlayer = DEFAULT_MAX_BUBBLES_PER_PLAYER;
        private float fov = DEFAULT_FOV;
        private int cleanupInterval = DEFAULT_CLEANUP_INTERVAL;

        SpeechBubbleConfig build() {
            return new SpeechBubbleConfig(this);
        }
    }

    public static float getDefaultFov() {
        return DEFAULT_FOV;
    }
}
