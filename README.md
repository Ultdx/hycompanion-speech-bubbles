# Hycompanion Speech Bubbles Plugin

A Hytale server plugin that provides floating speech bubble UI above NPCs and other entities.

## Features

- 🎈 **Floating Speech Bubbles** - Display text bubbles anchored to any entity
- ⏱️ **Configurable Duration** - Set how long bubbles remain visible
- 📐 **Customizable Dimensions** - Control max width and height
- 🎨 **Styling Options** - Customize text color and background opacity
- 👥 **Player-Specific** - Bubbles are shown to specific players or all players
- 🔄 **API for Other Plugins** - Simple API for integration with other plugins
- 🧹 **Automatic Cleanup** - Bubbles auto-remove after duration expires
- 🔌 **Optional Dependency** - Plugins can use it if available, work without it

## Installation

1. Build the plugin:
   ```
   compile-plugin.bat
   ```

2. Copy the generated JAR from `target/hycompanion-speech-bubbles.jar` to your Hytale server's `mods/` folder

3. The plugin will create a default `config.yml` on first run

## Configuration

Edit `mods/dev.hycompanion_SpeechBubbles/config.yml`:

```yaml
# Speech Bubbles Configuration

# Default settings for speech bubbles
defaults:
  # Default display duration in milliseconds
  duration: 5000
  # Default maximum width in pixels
  maxWidth: 250
  # Default maximum height in pixels
  maxHeight: 150
  # Default text color (hex)
  textColor: "#FFFFFF"
  # Default background opacity (0.0 - 1.0)
  backgroundOpacity: 0.9

# Maximum concurrent bubbles per player
maxBubblesPerPlayer: 10

# Cleanup interval in seconds
cleanupInterval: 30
```

## API Usage

### For Other Plugin Developers

The Speech Bubbles plugin provides a simple API for other plugins to display bubbles.

#### Method 1: Direct API Usage (Compile Dependency)

Add the plugin as a `provided` dependency and use the API directly:

```java
import dev.hycompanion.speechbubbles.api.SpeechBubbleAPI;
import dev.hycompanion.speechbubbles.api.SpeechBubbleOptions;

// Show a simple speech bubble
UUID npcUuid = ...;  // The NPC's entity UUID
UUID playerUuid = ...;  // The player's UUID
SpeechBubbleAPI.showBubble(npcUuid, playerUuid, "Hello, adventurer!");

// With custom duration
SpeechBubbleAPI.showBubble(npcUuid, playerUuid, "Welcome!", 8000);

// With full options
SpeechBubbleOptions options = new SpeechBubbleOptions()
    .duration(10000)
    .maxWidth(300)
    .textColor("#FFD700");
SpeechBubbleAPI.showBubble(npcUuid, playerUuid, "Check this out!", options);
```

#### Method 2: Using Hytale's PluginManager (Recommended - Optional Dependency)

For a **truly optional** dependency that doesn't require the Speech Bubbles JAR at compile time, use Hytale's built-in `PluginManager` with reflection:

```java
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import java.lang.reflect.Method;
import java.util.UUID;

public class MyPluginIntegration {
    
    // Note: Group is "dev.hycompanion.speech" (not "dev.hycompanion")
    private static final PluginIdentifier SPEECH_BUBBLES_ID = 
        new PluginIdentifier("dev.hycompanion.speech", "SpeechBubbles");
    
    private Method showBubbleMethod;
    private boolean available = false;
    
    public MyPluginIntegration() {
        detectSpeechBubbles();
    }
    
    private void detectSpeechBubbles() {
        try {
            // Get Hytale's PluginManager
            PluginManager pluginManager = HytaleServer.get().getPluginManager();
            
            // Look up the Speech Bubbles plugin
            PluginBase plugin = pluginManager.getPlugin(SPEECH_BUBBLES_ID);
            
            if (plugin == null) {
                System.out.println("Speech Bubbles plugin not found");
                return;
            }
            
            // Check if plugin is enabled
            if (plugin.getState() != PluginState.ENABLED) {
                System.out.println("Speech Bubbles not enabled: " + plugin.getState());
                return;
            }
            
            // Get the plugin's class loader to access its classes
            ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
            
            // Load the API class from the plugin
            Class<?> apiClass = Class.forName(
                "dev.hycompanion.speechbubbles.api.SpeechBubbleAPI",
                true,
                pluginClassLoader
            );
            
            // Cache the method we need
            showBubbleMethod = apiClass.getMethod("showBubble",
                UUID.class, UUID.class, String.class, long.class);
            
            available = true;
            System.out.println("Speech Bubbles v" + plugin.getManifest().getVersion() + " detected!");
            
        } catch (Exception e) {
            System.out.println("Speech Bubbles integration failed: " + e.getMessage());
        }
    }
    
    public boolean isAvailable() {
        return available;
    }
    
    public void showBubble(UUID entityUuid, UUID playerUuid, String text, long durationMs) {
        if (!available || showBubbleMethod == null) {
            return;
        }
        
        try {
            showBubbleMethod.invoke(null, entityUuid, playerUuid, text, durationMs);
        } catch (Exception e) {
            System.err.println("Failed to show bubble: " + e.getMessage());
        }
    }
}
```

### Complete Example: Wrapper Class Pattern

For production use, create a clean wrapper class (like Hycompanion does):

```java
public class SpeechBubbleIntegration {
    
    private static final PluginIdentifier PLUGIN_ID = 
        new PluginIdentifier("dev.hycompanion.speech", "SpeechBubbles");
    private static final String API_CLASS = 
        "dev.hycompanion.speechbubbles.api.SpeechBubbleAPI";
    
    private Method showBubbleMethod;
    private boolean available = false;
    
    public SpeechBubbleIntegration() {
        detectPlugin();
    }
    
    private void detectPlugin() {
        try {
            PluginManager pm = HytaleServer.get().getPluginManager();
            PluginBase plugin = pm.getPlugin(PLUGIN_ID);
            
            if (plugin == null || plugin.getState() != PluginState.ENABLED) {
                return;
            }
            
            ClassLoader cl = plugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(API_CLASS, true, cl);
            showBubbleMethod = apiClass.getMethod("showBubble", 
                UUID.class, UUID.class, String.class, long.class);
            
            available = true;
            
        } catch (Exception e) {
            // Plugin not available
        }
    }
    
    public boolean isAvailable() { return available; }
    
    public void showBubble(UUID entity, UUID player, String text, long duration) {
        if (available) {
            try { showBubbleMethod.invoke(null, entity, player, text, duration); }
            catch (Exception e) { /* ignore */ }
        }
    }
}
```

### API Reference

#### SpeechBubbleAPI

| Method | Description |
|--------|-------------|
| `isAvailable()` | Check if plugin is loaded |
| `showBubble(entity, player, text)` | Show simple bubble (5s) |
| `showBubble(entity, player, text, duration)` | Show with custom duration (ms) |
| `showBubble(entity, player, text, options)` | Show with full options |
| `showBubbleToAll(entity, text)` | Show to all players |
| `showBubbleToAll(entity, text, options)` | Show to all with options |
| `hideBubble(bubbleId)` | Hide specific bubble |
| `hideAllBubblesForPlayer(player)` | Hide all for player |
| `hideAllBubblesForEntity(entity)` | Hide all for entity |
| `getActiveBubbleCount(player)` | Count player's bubbles |

#### SpeechBubbleOptions

| Method | Default | Range |
|--------|---------|-------|
| `duration(ms)` | 5000 | 1000+ |
| `maxWidth(px)` | 250 | 100-500 |
| `maxHeight(px)` | 150 | 50-300 |
| `textColor(hex)` | #FFFFFF | Any hex |
| `backgroundOpacity()` | 0.9 | 0.0-1.0 |

## Integration with Hycompanion Plugin

Hycompanion uses the PluginManager approach with a clean wrapper. See the full example in `docs/HYCOMPANION_PLUGINMANAGER_INTEGRATION.java`:

```java
// In HytaleServerAdapter.java
private final PluginIntegrationManager integrationManager;

public HytaleServerAdapter(...) {
    // Initialize plugin integrations
    this.integrationManager = new PluginIntegrationManager(logger);
}

private void showSpeechBubble(UUID npcId, UUID playerId, String message) {
    SpeechBubbleIntegration bubbles = integrationManager.getSpeechBubbles();
    if (bubbles.isAvailable()) {
        String bubbleText = bubbles.truncateText(message, 150);
        bubbles.showBubble(npcId, playerId, bubbleText, 6000);
    }
}
```

The integration:
- ✅ Uses Hytale's built-in PluginManager
- ✅ No compile-time dependency on Speech Bubbles
- ✅ Gracefully handles missing plugin
- ✅ Clean, maintainable code

## Technical Details

### How It Works

1. The plugin uses Hytale's `UpdateAnchorUI` packet to display UI elements anchored to entities
2. The `UICommandBuilder` constructs commands to:
   - Append the SpeechBubble.ui asset
   - Set dynamic text content
   - Configure dimensions and styling
3. Bubbles are automatically removed after their duration expires
4. A cleanup task runs periodically to remove any missed bubbles

### UI Asset

The speech bubble UI is defined in `Common/UI/Custom/SpeechBubble.ui`:
- Custom background image (`speech_bubble.png`) with 9-slice scaling
- Text container with configurable max dimensions
- CSS-like styling for positioning and appearance

### Plugin Structure

```
SpeechBubbles Plugin
├── SpeechBubblesEntrypoint.java     # Main plugin class
├── api/
│   ├── SpeechBubbleAPI.java         # Public API
│   └── SpeechBubbleOptions.java     # Configuration options
├── manager/
│   └── SpeechBubbleManager.java     # Core bubble management
└── resources/
    └── Common/UI/Custom/
        ├── SpeechBubble.ui          # UI layout
        └── speech_bubble.png        # Background texture
```

## Building from Source

Requirements:
- Java 25 (OpenJDK)
- Maven 3.8+

```bash
# Clone or navigate to the project
cd hycompanion-speech-bubbles

# Build
mvn clean package

# The plugin JAR will be in target/hycompanion-speech-bubbles.jar
```

## Troubleshooting

### Bubbles Not Appearing

1. Check if plugin is loaded:
   ```java
   PluginManager pm = HytaleServer.get().getPluginManager();
   PluginBase plugin = pm.getPlugin(new PluginIdentifier("dev.hycompanion.speech", "SpeechBubbles"));
   System.out.println("Plugin state: " + (plugin != null ? plugin.getState() : "NOT FOUND"));
   ```

2. Verify the image file exists:
   - The `speech_bubble.png` should be in `Common/UI/Custom/`
   - It gets packaged in the JAR automatically

3. Check Hytale client console for UI errors (enable Diagnostic Mode in settings)

### Plugin Not Found

Make sure the JAR is in the server's `mods/` folder, not in a subdirectory.

## License

MIT License - See LICENSE file for details

## Support

- Website: https://hycompanion.dev
- Issues: Please report issues on the project repository
