package dev.hycompanion.speechbubbles.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.protocol.packets.interface_.UpdateAnchorUI;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hycompanion.speechbubbles.SpeechBubblesEntrypoint;
import dev.hycompanion.speechbubbles.api.SpeechBubbleOptions;
import dev.hycompanion.speechbubbles.config.SpeechBubbleConfig;
import dev.hycompanion.speechbubbles.model.SpeechBubble;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the lifecycle of speech bubbles.
 * 
 * This class handles:
 * - Creating and displaying speech bubbles
 * - Scheduling automatic removal after duration expires
 * - Tracking active bubbles per player and entity
 * - Cleaning up expired bubbles
 */
public class SpeechBubbleManager {

    // Unique ID generator for bubbles
    private static final AtomicLong bubbleIdCounter = new AtomicLong(0);
    
    // Active bubbles: bubbleId -> SpeechBubble
    private final ConcurrentHashMap<UUID, SpeechBubble> activeBubbles = new ConcurrentHashMap<>();
    
    // Player bubbles: playerUuid -> Set of bubbleIds
    private final ConcurrentHashMap<UUID, Set<UUID>> playerBubbles = new ConcurrentHashMap<>();
    
    // Entity bubbles: entityUuid -> Set of bubbleIds
    private final ConcurrentHashMap<UUID, Set<UUID>> entityBubbles = new ConcurrentHashMap<>();
    
    // Scheduler for cleanup tasks
    private final ScheduledExecutorService scheduler;
    
    private final SpeechBubbleConfig config;
    private final SpeechBubblesEntrypoint plugin;
    private volatile boolean shutdown = false;

    public SpeechBubbleManager(@Nonnull SpeechBubbleConfig config, @Nonnull SpeechBubblesEntrypoint plugin) {
        this.config = config;
        this.plugin = plugin;
        
        // Create daemon scheduler for cleanup and position update tasks
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "SpeechBubble-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic cleanup
        startCleanupTask();
        
        // Start position update task (60 FPS = ~16ms)
        startPositionUpdateTask();
    }

    /**
     * Show a speech bubble to a specific player.
     * 
     * @param entityUuid The entity to anchor the bubble to
     * @param playerUuid The player who should see the bubble
     * @param text The text to display
     * @param options Custom options (null for defaults)
     * @return true if successful
     */
    public boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text, @Nullable SpeechBubbleOptions options) {
        if (shutdown) {
            return false;
        }
        
        // Get entity position
        Vector3d entityPos = getEntityPosition(entityUuid);
        if (entityPos == null) {
            System.err.println("[SpeechBubbles] Could not find entity position for " + entityUuid);
            return false;
        }
        
        // Merge with defaults
        SpeechBubbleOptions effectiveOptions = (options != null ? options : new SpeechBubbleOptions())
            .merge(getDefaultOptions());
        
        // Check player bubble limit
        Set<UUID> playerBubbleSet = playerBubbles.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());
        if (playerBubbleSet.size() >= config.getMaxBubblesPerPlayer()) {
            // Remove oldest bubble for this player
            removeOldestBubbleForPlayer(playerUuid);
        }
        
        // Generate unique bubble ID
        UUID bubbleId = generateBubbleId();
        
        // Create bubble with entity position
        SpeechBubble bubble = new SpeechBubble(
            bubbleId,
            entityUuid,
            playerUuid,
            text,
            effectiveOptions,
            System.currentTimeMillis(),
            entityPos.getX(), entityPos.getY(), entityPos.getZ()
        );
        
        // Store bubble
        activeBubbles.put(bubbleId, bubble);
        playerBubbleSet.add(bubbleId);
        entityBubbles.computeIfAbsent(entityUuid, k -> ConcurrentHashMap.newKeySet()).add(bubbleId);
        
        // Send to player
        boolean sent = sendBubbleToPlayer(bubble);
        
        if (sent) {
            // Schedule removal
            scheduleBubbleRemoval(bubble);
        } else {
            // Clean up if failed to send
            removeBubble(bubbleId);
        }
        
        return sent;
    }

    /**
     * Show a speech bubble to all online players.
     * 
     * @param entityUuid The entity to anchor the bubble to
     * @param text The text to display
     * @param options Custom options (null for defaults)
     * @return number of players who received the bubble
     */
    public int showBubbleToAll(@Nonnull UUID entityUuid, @Nonnull String text, @Nullable SpeechBubbleOptions options) {
        int count = 0;
        
        try {
            Collection<PlayerRef> players = Universe.get().getPlayers();
            for (PlayerRef playerRef : players) {
                if (showBubble(entityUuid, playerRef.getUuid(), text, options)) {
                    count++;
                }
            }
        } catch (Exception e) {
            // Universe might not be available
        }
        
        return count;
    }

    /**
     * Hide all bubbles for a specific player.
     * 
     * @param playerUuid The player UUID
     * @return number of bubbles hidden
     */
    public int hideAllBubblesForPlayer(@Nonnull UUID playerUuid) {
        Set<UUID> bubbleIds = playerBubbles.remove(playerUuid);
        if (bubbleIds == null || bubbleIds.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        for (UUID bubbleId : new ArrayList<>(bubbleIds)) {
            if (hideBubble(bubbleId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Hide all bubbles for a specific entity.
     * 
     * @param entityUuid The entity UUID
     * @return number of bubbles hidden
     */
    public int hideAllBubblesForEntity(@Nonnull UUID entityUuid) {
        Set<UUID> bubbleIds = entityBubbles.remove(entityUuid);
        if (bubbleIds == null || bubbleIds.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        for (UUID bubbleId : new ArrayList<>(bubbleIds)) {
            if (hideBubble(bubbleId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Hide a specific bubble.
     * 
     * @param bubbleId The bubble UUID
     * @return true if a bubble was hidden
     */
    public boolean hideBubble(@Nonnull UUID bubbleId) {
        SpeechBubble bubble = activeBubbles.get(bubbleId);
        if (bubble == null) {
            return false;
        }
        
        // Send clear command to player
        sendClearBubble(bubble);
        
        // Remove from tracking
        removeBubble(bubbleId);
        
        return true;
    }

    /**
     * Get the number of active bubbles for a player.
     */
    public int getActiveBubbleCount(@Nonnull UUID playerUuid) {
        Set<UUID> bubbles = playerBubbles.get(playerUuid);
        return bubbles != null ? bubbles.size() : 0;
    }

    /**
     * Shutdown the manager and clean up all resources.
     */
    public void shutdown() {
        shutdown = true;
        
        // Hide all active bubbles
        for (UUID bubbleId : new ArrayList<>(activeBubbles.keySet())) {
            hideBubble(bubbleId);
        }
        
        // Shutdown scheduler
        scheduler.shutdownNow();
    }

    // ========== Private Helper Methods ==========

    /**
     * Generate a unique bubble ID.
     */
    @Nonnull
    private UUID generateBubbleId() {
        long counter = bubbleIdCounter.incrementAndGet();
        return new UUID(counter, System.currentTimeMillis());
    }

    /**
     * Send the bubble UI to the player.
     */
    private boolean sendBubbleToPlayer(@Nonnull SpeechBubble bubble) {

        System.out.println("[SpeechBubbles] Sending bubble to player " + bubble.getPlayerUuid() + " for entity " + bubble.getEntityUuid());
        
        try {
            PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
            if (playerRef == null) {
                return false;
            }
            
            // Calculate initial screen position
            Vector3d entityPos = new Vector3d(bubble.getEntityX(), bubble.getEntityY(), bubble.getEntityZ());
            int[] screenPos = project3DToScreen(entityPos, playerRef);
            
            if (screenPos == null) {
                // Entity is not visible initially
                return false;
            }
            
            bubble.setScreenPosition(screenPos[0], screenPos[1], true);
            
            // Build UI commands
            UICommandBuilder commandBuilder = new UICommandBuilder();
            
            // Append the UI document
            commandBuilder.append("SpeechBubble.ui");
            
            // Set the text content
            commandBuilder.set("#MessageText.Text", bubble.getText());
            
            // Set initial position using Anchor object
            Anchor anchor = new Anchor();
            anchor.setLeft(Value.of(Integer.valueOf(screenPos[0])));
            anchor.setTop(Value.of(Integer.valueOf(screenPos[1])));
            anchor.setWidth(Value.of(Integer.valueOf(313)));
            anchor.setHeight(Value.of(Integer.valueOf(175)));
            commandBuilder.setObject("#SpeechBubbleContainer.Anchor", anchor);
            
            CustomHud hudPacket = new CustomHud(
                false,  // Don't clear existing HUD
                commandBuilder.getCommands()
            );
            
            // Send to player
            playerRef.getPacketHandler().writeNoCache(hudPacket);
            
            System.out.println("[SpeechBubbles] Bubble sent to player " + bubble.getPlayerUuid() + 
                " at screen (" + screenPos[0] + ", " + screenPos[1] + ")");
            
            return true;
            
        } catch (Exception e) {
            // Log the error for debugging
            System.err.println("[SpeechBubbles] Failed to send bubble to player " + bubble.getPlayerUuid() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Send a command to clear the bubble from the player's UI.
     */
    private void sendClearBubble(@Nonnull SpeechBubble bubble) {
        try {
            PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
            if (playerRef == null) {
                return;
            }
            
            // Create clear packet - clear HUD
            // Use empty command array instead of null to avoid NRE on client
            CustomHud hudPacket = new CustomHud(
                true,   // Clear HUD
                new UICommandBuilder().getCommands()  // Empty commands
            );
            
            playerRef.getPacketHandler().writeNoCache(hudPacket);
            
        } catch (Exception e) {
            // Ignore errors during cleanup
        }
    }

    /**
     * Schedule the automatic removal of a bubble after its duration expires.
     */
    private void scheduleBubbleRemoval(@Nonnull SpeechBubble bubble) {
        long delay = bubble.getOptions().getDuration();
        
        scheduler.schedule(() -> {
            hideBubble(bubble.getBubbleId());
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Remove a bubble from all tracking maps.
     */
    private void removeBubble(@Nonnull UUID bubbleId) {
        SpeechBubble bubble = activeBubbles.remove(bubbleId);
        if (bubble != null) {
            Set<UUID> playerSet = playerBubbles.get(bubble.getPlayerUuid());
            if (playerSet != null) {
                playerSet.remove(bubbleId);
            }
            
            Set<UUID> entitySet = entityBubbles.get(bubble.getEntityUuid());
            if (entitySet != null) {
                entitySet.remove(bubbleId);
            }
        }
    }

    /**
     * Remove the oldest bubble for a player (FIFO).
     */
    private void removeOldestBubbleForPlayer(@Nonnull UUID playerUuid) {
        Set<UUID> bubbleIds = playerBubbles.get(playerUuid);
        if (bubbleIds == null || bubbleIds.isEmpty()) {
            return;
        }
        
        // Find oldest bubble
        SpeechBubble oldest = null;
        UUID oldestId = null;
        
        for (UUID bubbleId : bubbleIds) {
            SpeechBubble bubble = activeBubbles.get(bubbleId);
            if (bubble != null) {
                if (oldest == null || bubble.getCreatedAt() < oldest.getCreatedAt()) {
                    oldest = bubble;
                    oldestId = bubbleId;
                }
            }
        }
        
        if (oldestId != null) {
            hideBubble(oldestId);
        }
    }

    /**
     * Start the periodic cleanup task.
     */
    private void startCleanupTask() {
        long interval = config.getCleanupInterval();
        
        scheduler.scheduleAtFixedRate(() -> {
            if (shutdown) {
                return;
            }
            
            cleanupExpiredBubbles();
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Clean up any expired bubbles that might have been missed.
     */
    private void cleanupExpiredBubbles() {
        long now = System.currentTimeMillis();
        
        for (Map.Entry<UUID, SpeechBubble> entry : activeBubbles.entrySet()) {
            SpeechBubble bubble = entry.getValue();
            long expiryTime = bubble.getCreatedAt() + bubble.getOptions().getDuration();
            
            if (now >= expiryTime) {
                hideBubble(entry.getKey());
            }
        }
    }

    /**
     * Start the position update task that updates bubble screen positions.
     */
    private void startPositionUpdateTask() {
        scheduler.scheduleAtFixedRate(() -> {
            if (shutdown) {
                return;
            }
            
            updateBubblePositions();
        }, 50, 50, TimeUnit.MILLISECONDS); // 20 updates per second
    }
    
    /**
     * Update all bubble positions to follow their entities.
     */
    private void updateBubblePositions() {
        for (SpeechBubble bubble : activeBubbles.values()) {
            try {
                PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
                if (playerRef == null) {
                    continue;
                }
                
                // Get current entity position
                Vector3d entityPos = getEntityPosition(bubble.getEntityUuid());
                if (entityPos == null) {
                    continue;
                }
                
                // Update stored position
                bubble.setEntityPosition(entityPos.getX(), entityPos.getY(), entityPos.getZ());
                
                // Project to screen
                int[] screenPos = project3DToScreen(entityPos, playerRef);
                if (screenPos == null) {
                    // Entity is behind camera or too far - hide bubble
                    if (bubble.isVisible()) {
                        bubble.setScreenPosition(-1, -1, false);
                        clearBubbleAtPosition(playerRef, bubble);
                    }
                    continue;
                }
                
                // Update bubble position
                bubble.setScreenPosition(screenPos[0], screenPos[1], true);
                updateBubblePosition(playerRef, bubble, screenPos[0], screenPos[1]);
                
            } catch (Exception e) {
                // Ignore errors during position update
            }
        }
    }
    
    /**
     * Update a single bubble's position on screen.
     */
    private void updateBubblePosition(@Nonnull PlayerRef playerRef, @Nonnull SpeechBubble bubble, int screenX, int screenY) {
        try {
            UICommandBuilder commandBuilder = new UICommandBuilder();
            
            // Update the container position using Anchor object
            Anchor anchor = new Anchor();
            anchor.setLeft(Value.of(Integer.valueOf(screenX)));
            anchor.setTop(Value.of(Integer.valueOf(screenY)));
            anchor.setWidth(Value.of(Integer.valueOf(313)));
            anchor.setHeight(Value.of(Integer.valueOf(175)));
            commandBuilder.setObject("#SpeechBubbleContainer.Anchor", anchor);
            
            CustomHud hudPacket = new CustomHud(
                false,  // Don't clear
                commandBuilder.getCommands()
            );
            
            playerRef.getPacketHandler().writeNoCache(hudPacket);
            
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * Clear a bubble from screen (hide it).
     */
    private void clearBubbleAtPosition(@Nonnull PlayerRef playerRef, @Nonnull SpeechBubble bubble) {
        try {
            CustomHud hudPacket = new CustomHud(
                true,   // Clear
                new UICommandBuilder().getCommands()
            );
            
            playerRef.getPacketHandler().writeNoCache(hudPacket);
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * Get default options from config.
     */
    @Nonnull
    private SpeechBubbleOptions getDefaultOptions() {
        return new SpeechBubbleOptions()
            .duration(config.getDefaultDuration())
            .maxWidth(config.getDefaultMaxWidth())
            .maxHeight(config.getDefaultMaxHeight())
            .textColor(config.getDefaultTextColor())
            .backgroundOpacity(config.getDefaultBackgroundOpacity());
    }
    
    /**
     * Get entity position from the world.
     * 
     * @param entityUuid The entity UUID
     * @return The entity's position, or null if not found
     */
    @Nullable
    private Vector3d getEntityPosition(@Nonnull UUID entityUuid) {
        try {
            // Try to find entity in all worlds
            for (World world : Universe.get().getWorlds().values()) {
                EntityStore entityStore = world.getEntityStore();
                if (entityStore != null) {
                    Ref<EntityStore> entityRef = entityStore.getRefFromUUID(entityUuid);
                    if (entityRef != null && entityRef.isValid()) {
                        Store<EntityStore> store = entityRef.getStore();
                        TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                        if (transform != null) {
                            return transform.getPosition();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Universe might not be available
        }
        return null;
    }
    
    /**
     * Project 3D world position to 2D screen coordinates.
     * This is a simplified projection that approximates screen position.
     * 
     * @param entityPos Entity position in world
     * @param playerRef Player reference for camera position
     * @return Array [screenX, screenY] or null if behind camera
     */
    @Nullable
    private int[] project3DToScreen(@Nonnull Vector3d entityPos, @Nonnull PlayerRef playerRef) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return null;
            }
            
            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }
            
            Vector3d playerPos = transform.getPosition();
            
            // Calculate relative position (entity - player)
            double dx = entityPos.getX() - playerPos.getX();
            double dy = entityPos.getY() - playerPos.getY() + 2.0; // Offset above entity head
            double dz = entityPos.getZ() - playerPos.getZ();
            
            // Simple distance check - don't show if too far
            double distanceSquared = dx*dx + dz*dz;
            if (distanceSquared > 2500) { // 50 blocks
                return null;
            }
            
            // Simplified projection (assuming default FOV and screen size)
            // This is an approximation - true projection requires client camera data
            double distance = Math.sqrt(distanceSquared);
            
            // Normalize to screen coordinates (1920x1080 default)
            // Center of screen is (960, 540)
            int screenCenterX = 960;
            int screenCenterY = 540;
            
            // Scale factor based on distance (closer = larger movement)
            double scale = 500.0 / Math.max(distance, 1.0);
            
            // Project to screen (simplified)
            int screenX = screenCenterX + (int)(dx * scale);
            int screenY = screenCenterY - (int)(dy * scale); // Y is inverted
            
            // Offset so bubble appears ABOVE entity's head
            // Bubble size: 313x175 (50% of original 626x349)
            int bubbleWidth = 313;
            int bubbleHeight = 175;
            int tailTipX = 40;   // 80 * 0.5 = 40
            int tailTipY = 160;  // 320 * 0.5 = 160
            
            // Shift so the tail tip aligns with the entity position
            // Subtract tail position so entity is at the tail tip
            screenX = screenX - tailTipX;
            screenY = screenY - tailTipY;
            
            // Clamp to screen bounds
            screenX = Math.max(0, Math.min(1920 - bubbleWidth, screenX));
            screenY = Math.max(0, Math.min(1080 - bubbleHeight, screenY));
            
            return new int[]{screenX, screenY};
            
        } catch (Exception e) {
            return null;
        }
    }
}
