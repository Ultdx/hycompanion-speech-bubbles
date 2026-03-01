package dev.hycompanion.speechbubbles;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import dev.hycompanion.speechbubbles.api.SpeechBubbleAPI;
import dev.hycompanion.speechbubbles.config.SpeechBubbleConfig;
import dev.hycompanion.speechbubbles.manager.SpeechBubbleManager;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Speech Bubbles Plugin Entry Point for Hytale Server
 * 
 * This plugin provides speech bubble UI functionality for NPCs and other entities.
 * Other plugins can use the SpeechBubbleAPI to display floating text bubbles above entities.
 * 
 * <p><b>Asset Pack Loading:</b></p>
 * <p>This plugin includes a server-side asset pack (manifest.json has "IncludesAssetPack": true).
 * Hytale automatically handles asset pack loading from plugin JAR files - no manual extraction needed.</p>
 * 
 * <p>The UI document is located at <code>Common/UI/Custom/SpeechBubble.ui</code> within the JAR.
 * When referencing UI files in code, use paths relative to <code>Common/UI/</code>:</p>
 * <ul>
 *     <li>Files in <code>Common/UI/Custom/</code> → reference as <code>Custom/Filename.ui</code></li>
 *     <li>Files in <code>Common/UI/Pages/</code> → reference as <code>Pages/Filename.ui</code></li>
 *     <li>Files in <code>Common/UI/Hud/</code> → reference as <code>Hud/Filename.ui</code></li>
 * </ul>
 * 
 * @author Hycompanion Team
 * @version 1.0.0
 * @see dev.hycompanion.speechbubbles.api.SpeechBubbleAPI
 */
public class SpeechBubblesEntrypoint extends JavaPlugin {

    private static final HytaleLogger HYTALE_LOGGER = HytaleLogger.forEnclosingClass();
    public static final String VERSION = "1.0.0";

    private static SpeechBubblesEntrypoint instance;
    
    private SpeechBubbleManager bubbleManager;
    private SpeechBubbleConfig config;
    private Path dataFolder;
    
    /**
     * Constructor required by Hytale plugin loader
     * 
     * @param init Plugin initialization data provided by Hytale
     */
    public SpeechBubblesEntrypoint(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        HYTALE_LOGGER.atInfo().log("SpeechBubbles plugin constructor called - version " + VERSION);
    }

    /**
     * Get the plugin instance
     */
    public static SpeechBubblesEntrypoint getInstance() {
        return instance;
    }

    /**
     * Setup phase - runs BEFORE asset loading
     */
    @Override
    protected void setup() {
        HYTALE_LOGGER.atInfo().log("SpeechBubbles setup() starting...");
        
        try {
            // Get data folder
            dataFolder = getDataDirectory();
            HYTALE_LOGGER.atInfo().log("Data directory: " + dataFolder.toString());
            
            Files.createDirectories(dataFolder);
            
            // Copy default config if needed
            copyDefaultConfig();
            
            // Load configuration
            config = SpeechBubbleConfig.load(dataFolder.resolve("config.yml"));
            HYTALE_LOGGER.atInfo().log("Configuration loaded");
            
            // Register for shutdown event
            getEventRegistry().register(ShutdownEvent.class, this::onServerShutdown);
            
            HYTALE_LOGGER.atInfo().log("SpeechBubbles setup() complete");
            
        } catch (Exception e) {
            HYTALE_LOGGER.atSevere().log("Failed during setup phase: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Start phase - runs AFTER asset loading
     */
    @Override
    protected void start() {
        HYTALE_LOGGER.atInfo().log("SpeechBubbles start() beginning...");
        
        try {
            // Ensure data folder exists
            if (dataFolder == null) {
                dataFolder = getDataDirectory();
                Files.createDirectories(dataFolder);
            }
            
            // Reload config if needed
            if (config == null) {
                copyDefaultConfig();
                config = SpeechBubbleConfig.load(dataFolder.resolve("config.yml"));
            }
            
            // Initialize the speech bubble manager
            bubbleManager = new SpeechBubbleManager(config, this);
            
            HYTALE_LOGGER.atInfo().log("======================================");
            HYTALE_LOGGER.atInfo().log("  Speech Bubbles Plugin v" + VERSION);
            HYTALE_LOGGER.atInfo().log("  https://hycompanion.dev");
            HYTALE_LOGGER.atInfo().log("======================================");
            HYTALE_LOGGER.atInfo().log("Asset pack included: Custom/SpeechBubble.ui");
            HYTALE_LOGGER.atInfo().log("SpeechBubbles enabled successfully!");
            
        } catch (Exception e) {
            HYTALE_LOGGER.atSevere().log("Failed to enable SpeechBubbles: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Shutdown phase - cleanup resources
     */
    @Override
    protected void shutdown() {
        HYTALE_LOGGER.atInfo().log("SpeechBubbles shutdown() starting...");
        
        if (bubbleManager != null) {
            bubbleManager.shutdown();
        }
        
        instance = null;
        HYTALE_LOGGER.atInfo().log("SpeechBubbles shutdown() complete");
    }

    /**
     * Handle server shutdown event
     */
    private void onServerShutdown(ShutdownEvent event) {
        HYTALE_LOGGER.atInfo().log("SpeechBubbles received shutdown event");
        if (bubbleManager != null) {
            bubbleManager.shutdown();
        }
    }

    /**
     * Copy default config.yml from resources if it doesn't exist
     */
    private void copyDefaultConfig() {
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
                    HYTALE_LOGGER.atInfo().log("Default config.yml created at " + configPath);
                } else {
                    // Create default config manually
                    String defaultConfig = """
                        # Speech Bubbles Configuration
                        
                        # Default settings for speech bubbles
                        defaults:
                          # Default display duration in milliseconds
                          duration: 5000
                          # Default maximum width in pixels (626 = original bubble image width)
                          maxWidth: 626
                          # Default maximum height in pixels (349 = original bubble image height)
                          maxHeight: 349
                          # Default text color (hex)
                          textColor: "#FFFFFF"
                          # Default background opacity (0.0 - 1.0)
                          backgroundOpacity: 0.9
                          # Field of view in degrees (for 3D to screen projection)
                          fov: 75.0
                        
                        # Maximum concurrent bubbles per player
                        maxBubblesPerPlayer: 10
                        
                        # Cleanup interval in seconds
                        cleanupInterval: 30
                        """;
                    Files.writeString(configPath, defaultConfig);
                    HYTALE_LOGGER.atInfo().log("Default config.yml created at " + configPath);
                }
            } catch (IOException e) {
                HYTALE_LOGGER.atWarning().log("Failed to copy default config: " + e.getMessage());
            }
        }
    }

    // ========== Getters ==========

    public SpeechBubbleManager getBubbleManager() {
        return bubbleManager;
    }

    public SpeechBubbleConfig getConfig() {
        return config;
    }

    public Path getDataFolder() {
        return dataFolder;
    }
}
