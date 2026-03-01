package dev.hycompanion.speechbubbles.manager;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.matrix.Matrix4d;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
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
     * @param text       The text to display
     * @param options    Custom options (null for defaults)
     * @return true if successful
     */
    public boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text,
            @Nullable SpeechBubbleOptions options) {
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
        SpeechBubbleOptions defaultOptions = getDefaultOptions();
        System.out.println("[SpeechBubbles] Config default maxWidth=" + config.getDefaultMaxWidth()
                + ", maxHeight=" + config.getDefaultMaxHeight());
        System.out.println("[SpeechBubbles] Options default maxWidth=" + defaultOptions.getMaxWidth()
                + ", maxHeight=" + defaultOptions.getMaxHeight());
        System.out.println("[SpeechBubbles] User provided options: " + (options != null ? "yes" : "no"));
        if (options != null) {
            System.out.println("[SpeechBubbles] User options maxWidth=" + options.getMaxWidth()
                    + ", maxHeight=" + options.getMaxHeight());
        }

        SpeechBubbleOptions effectiveOptions = (options != null ? options : new SpeechBubbleOptions())
                .merge(defaultOptions);
        System.out.println("[SpeechBubbles] Effective options maxWidth=" + effectiveOptions.getMaxWidth()
                + ", maxHeight=" + effectiveOptions.getMaxHeight());

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
                entityPos.getX(), entityPos.getY(), entityPos.getZ());

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
     * @param text       The text to display
     * @param options    Custom options (null for defaults)
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
     * Calculate bubble dimensions based on text length.
     * 
     * @param text      The text to display
     * @param maxWidth  Maximum width constraint from options
     * @param maxHeight Maximum height constraint from options
     * @return int array [bubbleWidth, bubbleHeight, textWidth, textHeight,
     *         tailTipX, tailTipY]
     */
    private int[] calculateBubbleDimensions(@Nonnull String text, int maxWidth, int maxHeight) {
        System.out.println("[SpeechBubbles] === calculateBubbleDimensions START ===");
        System.out.println("[SpeechBubbles] Input text: \"" + text + "\"");
        System.out.println("[SpeechBubbles] Input text length: " + text.length());
        System.out.println("[SpeechBubbles] Input maxWidth: " + maxWidth + ", maxHeight: " + maxHeight);

        // Constants for sizing calculations
        final int FONT_SIZE = 24;
        final float AVG_CHAR_WIDTH = FONT_SIZE * 0.55f; // Average char width ~55% of font size
        final int LINE_HEIGHT = (int) (FONT_SIZE * 1.3f); // Line height with spacing
        final int HORIZONTAL_PADDING = 50; // Padding around text (25px each side)
        final int VERTICAL_PADDING = 75; // Padding around text (increased to prevent text overflow)
        final int MIN_TEXT_WIDTH = 80; // Minimum text area width
        final int MIN_TEXT_HEIGHT = 30; // Minimum text area height (one line)
        final int MAX_TEXT_WIDTH = maxWidth - HORIZONTAL_PADDING;
        final int MAX_TEXT_HEIGHT = maxHeight - VERTICAL_PADDING;

        System.out.println("[SpeechBubbles] Constants: FONT_SIZE=" + FONT_SIZE + ", AVG_CHAR_WIDTH=" + AVG_CHAR_WIDTH
                + ", LINE_HEIGHT=" + LINE_HEIGHT);
        System.out.println(
                "[SpeechBubbles] Padding: HORIZONTAL=" + HORIZONTAL_PADDING + ", VERTICAL=" + VERTICAL_PADDING);
        System.out.println(
                "[SpeechBubbles] Constraints: MIN_TEXT_WIDTH=" + MIN_TEXT_WIDTH + ", MAX_TEXT_WIDTH=" + MAX_TEXT_WIDTH
                        + ", MIN_TEXT_HEIGHT=" + MIN_TEXT_HEIGHT + ", MAX_TEXT_HEIGHT=" + MAX_TEXT_HEIGHT);

        // Calculate text area width based on content
        int textLength = text.length();
        int estimatedTextWidth = (int) (textLength * AVG_CHAR_WIDTH);
        System.out.println("[SpeechBubbles] estimatedTextWidth = " + textLength + " * " + AVG_CHAR_WIDTH + " = "
                + estimatedTextWidth);

        // Clamp text width to constraints
        int textWidth = Math.max(MIN_TEXT_WIDTH, Math.min(estimatedTextWidth, MAX_TEXT_WIDTH));
        System.out.println("[SpeechBubbles] textWidth after clamping: " + textWidth + " (min=" + MIN_TEXT_WIDTH
                + ", max=" + MAX_TEXT_WIDTH + ")");

        // Calculate number of lines needed (approximate word wrapping)
        int charsPerLine = Math.max(1, (int) (textWidth / AVG_CHAR_WIDTH));
        int numLines = Math.max(1, (int) Math.ceil((double) textLength / charsPerLine));
        System.out.println("[SpeechBubbles] charsPerLine=" + charsPerLine + ", numLines=" + numLines);

        // Calculate text height based on number of lines
        int textHeight = Math.max(MIN_TEXT_HEIGHT, Math.min(numLines * LINE_HEIGHT, MAX_TEXT_HEIGHT));
        System.out.println("[SpeechBubbles] textHeight=" + textHeight + " (raw=" + (numLines * LINE_HEIGHT)
                + ", min=" + MIN_TEXT_HEIGHT + ", max=" + MAX_TEXT_HEIGHT + ")");

        // Calculate bubble dimensions
        int bubbleWidth = textWidth + HORIZONTAL_PADDING;
        int bubbleHeight = textHeight + VERTICAL_PADDING;
        System.out.println("[SpeechBubbles] bubbleWidth=" + bubbleWidth + " (textWidth=" + textWidth + " + padding="
                + HORIZONTAL_PADDING + ")");
        System.out.println("[SpeechBubbles] bubbleHeight=" + bubbleHeight + " (textHeight=" + textHeight + " + padding="
                + VERTICAL_PADDING + ")");

        // Calculate tail tip position (proportional to bubble size)
        // Original image: tail tip at 80,319 on a 626x349 image
        int tailTipX = (int) (bubbleWidth * (80.0 / 626.0)); // ~12.8% of bubble width
        int tailTipY = (int) (bubbleHeight * (319.0 / 349.0)); // ~91.4% of bubble height
        System.out.println("[SpeechBubbles] tailTipX=" + tailTipX + ", tailTipY=" + tailTipY);
        System.out.println("[SpeechBubbles] === calculateBubbleDimensions END ===");

        return new int[] { bubbleWidth, bubbleHeight, textWidth, textHeight, tailTipX, tailTipY, numLines };
    }

    /**
     * Truncate text to fit within the maximum number of lines, adding "..." if
     * truncated.
     * 
     * @param text         The original text
     * @param textWidth    The width of the text area
     * @param maxLines     Maximum number of lines allowed
     * @param avgCharWidth Average character width
     * @return Truncated text with "..." if needed
     */
    @Nonnull
    private String truncateText(@Nonnull String text, int textWidth, int maxLines, float avgCharWidth) {
        int charsPerLine = Math.max(1, (int) (textWidth / avgCharWidth));
        int maxChars = charsPerLine * maxLines;

        if (text.length() <= maxChars) {
            return text;
        }

        // Reserve space for "..."
        int truncateLength = maxChars - 3;
        if (truncateLength < 1) {
            truncateLength = Math.max(1, maxChars);
        }

        String truncated = text.substring(0, truncateLength) + "...";
        System.out.println(
                "[SpeechBubbles] Text truncated from " + text.length() + " to " + truncated.length() + " chars");
        return truncated;
    }

    /**
     * Send the bubble UI to the player.
     */
    private boolean sendBubbleToPlayer(@Nonnull SpeechBubble bubble) {

        System.out.println("[SpeechBubbles] Sending bubble to player " + bubble.getPlayerUuid() + " for entity "
                + bubble.getEntityUuid());

        try {
            PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
            if (playerRef == null) {
                return false;
            }

            // Calculate dynamic dimensions based on text first
            int[] dimensions = calculateBubbleDimensions(
                    bubble.getText(),
                    bubble.getOptions().getMaxWidth(),
                    bubble.getOptions().getMaxHeight());
            int bubbleWidth = dimensions[0];
            int bubbleHeight = dimensions[1];
            int textWidth = dimensions[2];
            int textHeight = dimensions[3];
            int tailTipX = dimensions[4];
            int tailTipY = dimensions[5];
            int numLines = dimensions[6];

            // Store dimensions in bubble for position updates
            bubble.setDimensions(bubbleWidth, bubbleHeight, tailTipX, tailTipY);

            // Calculate initial screen position using the actual bubble dimensions
            Vector3d entityPos = new Vector3d(bubble.getEntityX(), bubble.getEntityY(), bubble.getEntityZ());
            int[] screenPos = project3DToScreen(entityPos, playerRef, bubbleWidth, bubbleHeight, tailTipX, tailTipY,
                    bubble.getOptions().getFov());

            if (screenPos == null) {
                // Entity is not visible initially
                return false;
            }

            bubble.setScreenPosition(screenPos[0], screenPos[1], true);

            // Check if text needs truncation
            final int FONT_SIZE = 24;
            final int LINE_HEIGHT = (int) (FONT_SIZE * 1.3f);
            final int VERTICAL_PADDING = 75;
            int maxTextHeight = bubble.getOptions().getMaxHeight() - VERTICAL_PADDING;
            int maxLines = Math.max(1, maxTextHeight / LINE_HEIGHT);

            String displayText = bubble.getText();
            if (numLines > maxLines) {
                displayText = truncateText(bubble.getText(), textWidth, maxLines, FONT_SIZE * 0.55f);
            }

            // Build UI commands
            UICommandBuilder commandBuilder = new UICommandBuilder();

            // Append the UI document
            commandBuilder.append("SpeechBubble.ui");

            // Set the text content (possibly truncated)
            commandBuilder.set("#MessageText.Text", displayText);

            // Set text color if specified
            String textColor = bubble.getOptions().getTextColor();
            if (!textColor.equals(SpeechBubbleOptions.DEFAULT_TEXT_COLOR)) {
                commandBuilder.set("#MessageText.Style.TextColor", textColor);
            }

            // Set dynamic text area dimensions
            System.out.println("[SpeechBubbles] Setting #TextArea.Anchor: Left=25, Top=25, Width=" + textWidth
                    + ", Height=" + textHeight);
            Anchor textAreaAnchor = new Anchor();
            textAreaAnchor.setLeft(Value.of(Integer.valueOf(25))); // Left padding
            textAreaAnchor.setTop(Value.of(Integer.valueOf(25))); // Top padding
            textAreaAnchor.setWidth(Value.of(Integer.valueOf(textWidth)));
            textAreaAnchor.setHeight(Value.of(Integer.valueOf(textHeight)));
            commandBuilder.setObject("#TextArea.Anchor", textAreaAnchor);

            // Set container dimensions and position
            System.out.println("[SpeechBubbles] Setting #SpeechBubbleContainer.Anchor: Left=" + screenPos[0] + ", Top="
                    + screenPos[1]
                    + ", Width=" + bubbleWidth + ", Height=" + bubbleHeight);
            Anchor containerAnchor = new Anchor();
            containerAnchor.setLeft(Value.of(Integer.valueOf(screenPos[0])));
            containerAnchor.setTop(Value.of(Integer.valueOf(screenPos[1])));
            containerAnchor.setWidth(Value.of(Integer.valueOf(bubbleWidth)));
            containerAnchor.setHeight(Value.of(Integer.valueOf(bubbleHeight)));
            commandBuilder.setObject("#SpeechBubbleContainer.Anchor", containerAnchor);

            CustomHud hudPacket = new CustomHud(
                    false, // Don't clear existing HUD
                    commandBuilder.getCommands());

            // Send to player
            playerRef.getPacketHandler().writeNoCache(hudPacket);

            System.out.println("[SpeechBubbles] Bubble SENT to player " + bubble.getPlayerUuid() +
                    " at screen (" + screenPos[0] + ", " + screenPos[1] + ") with container size " + bubbleWidth + "x"
                    + bubbleHeight
                    + ", text area " + textWidth + "x" + textHeight);

            return true;

        } catch (Exception e) {
            // Log the error for debugging
            System.err.println("[SpeechBubbles] Failed to send bubble to player " + bubble.getPlayerUuid() + ": "
                    + e.getMessage());
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
                    true, // Clear HUD
                    new UICommandBuilder().getCommands() // Empty commands
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
     * 
     * NOTE: Position updates must run on the world thread to access entity data safely.
     * We schedule on the default world, but this means bubbles only update correctly
     * when the player is in the default world. For multi-world support, this would need
     * to be enhanced to track which world each bubble is in.
     */
    private void startPositionUpdateTask() {
        System.out.println("[SpeechBubbles] Starting position update task (every 50ms on world thread)");
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (shutdown) {
                    return;
                }
                
                // Schedule the actual update on the world thread
                // We use the default world for simplicity - player and entity should be in same world
                World world = Universe.get().getDefaultWorld();
                if (world != null) {
                    world.execute(() -> {
                        try {
                            if (!shutdown) {
                                updateBubblePositions();
                            }
                        } catch (Exception e) {
                            System.err.println("[SpeechBubbles] Exception in world thread update: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                System.err.println("[SpeechBubbles] Exception scheduling position update: " + e.getMessage());
                e.printStackTrace();
            }
        }, 50, 50, TimeUnit.MILLISECONDS); // 20 updates per second
    }

    /**
     * Update all bubble positions to follow their entities.
     * 
     * NOTE: This method now runs on the world thread (via world.execute()),
     * so it can safely access entity and player transform data.
     */
    private void updateBubblePositions() {
        int bubbleCount = activeBubbles.size();

        // Debug: log when task runs
        if (bubbleCount > 0) {
            System.out.println("[SpeechBubbles] updateBubblePositions running with " + bubbleCount + " active bubbles");
        }

        if (activeBubbles.isEmpty()) {
            return;
        }

        int updateCount = 0;

        for (SpeechBubble bubble : activeBubbles.values()) {
            try {
                System.out.println("[SpeechBubbles] Processing bubble for entity " + bubble.getEntityUuid());

                PlayerRef playerRef = Universe.get().getPlayer(bubble.getPlayerUuid());
                if (playerRef == null) {
                    System.out.println("[SpeechBubbles] Player not found for bubble");
                    continue;
                }

                // Get FRESH entity position from world (safe to do on world thread)
                Vector3d entityPos = getEntityPosition(bubble.getEntityUuid());
                if (entityPos != null) {
                    // Update stored position
                    bubble.setEntityPosition(entityPos.getX(), entityPos.getY(), entityPos.getZ());
                    System.out.println("[SpeechBubbles] Entity pos: " + entityPos + ", bubble stored pos: ("
                            + bubble.getEntityX() + "," + bubble.getEntityY() + "," + bubble.getEntityZ() + ")");
                } else {
                    // Use stored position if entity not found
                    System.out.println("[SpeechBubbles] Entity position not found, using stored position: ("
                            + bubble.getEntityX() + "," + bubble.getEntityY() + "," + bubble.getEntityZ() + ")");
                    entityPos = new Vector3d(bubble.getEntityX(), bubble.getEntityY(), bubble.getEntityZ());
                }

                // Project to screen using FRESH player camera data (safe on world thread)
                int[] screenPos = project3DToScreen(entityPos, playerRef, bubble.getBubbleWidth(),
                        bubble.getBubbleHeight(), bubble.getTailTipX(), bubble.getTailTipY(),
                        bubble.getOptions().getFov());
                        
                if (screenPos == null) {
                    // Entity is behind camera or too far - hide bubble by moving off-screen
                    // Note: We do NOT clear the HUD here because that would remove ALL bubbles
                    // for this player. Instead, we just mark this bubble as not visible.
                    // The bubble will be cleaned up when its duration expires.
                    if (bubble.isVisible()) {
                        System.out.println("[SpeechBubbles] Entity behind camera - marking bubble invisible");
                        bubble.setVisible(false);
                        // Move bubble off-screen instead of clearing HUD
                        updateBubblePosition(playerRef, bubble, -9999, -9999);
                    }
                    continue;
                } else {
                    // Ensure bubble is marked visible if it was previously hidden
                    if (!bubble.isVisible()) {
                        bubble.setVisible(true);
                    }
                }

                // Only update if position changed significantly (more than 2 pixels)
                int oldX = bubble.getScreenX();
                int oldY = bubble.getScreenY();
                int newX = screenPos[0];
                int newY = screenPos[1];

                int deltaX = Math.abs(newX - oldX);
                int deltaY = Math.abs(newY - oldY);

                // Debug: always log position check
                System.out.println("[SpeechBubbles] Position check: old=(" + oldX + "," + oldY + ") new=(" + newX + ","
                        + newY + ") delta=(" + deltaX + "," + deltaY + ") visible=" + bubble.isVisible());

                if (deltaX > 2 || deltaY > 2 || !bubble.isVisible()) {
                    bubble.setScreenPosition(newX, newY, true);
                    updateBubblePosition(playerRef, bubble, newX, newY);
                    updateCount++;

                    System.out.println("[SpeechBubbles] Position UPDATE SENT #" + updateCount + ": (" + oldX + ","
                            + oldY + ") -> (" + newX + "," + newY + ")");
                }

            } catch (Exception e) {
                // Log all errors for debugging
                System.err.println("[SpeechBubbles] Error in updateBubblePositions loop: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("[SpeechBubbles] Position update cycle complete: " + updateCount + "/" + bubbleCount
                + " bubbles updated");
    }

    /**
     * Update a single bubble's position on screen.
     */
    private void updateBubblePosition(@Nonnull PlayerRef playerRef, @Nonnull SpeechBubble bubble, int screenX,
            int screenY) {
        try {
            // Use UICommandBuilder to update the anchor position dynamically
            // This is more efficient than clearing and re-sending the entire UI
            UICommandBuilder commandBuilder = new UICommandBuilder();

            // Update the container position using stored dimensions
            Anchor anchor = new Anchor();
            anchor.setLeft(Value.of(Integer.valueOf(screenX)));
            anchor.setTop(Value.of(Integer.valueOf(screenY)));
            anchor.setWidth(Value.of(Integer.valueOf(bubble.getBubbleWidth())));
            anchor.setHeight(Value.of(Integer.valueOf(bubble.getBubbleHeight())));
            commandBuilder.setObject("#SpeechBubbleContainer.Anchor", anchor);

            // Send update without clearing existing UI (false = don't clear)
            CustomHud hudPacket = new CustomHud(false, commandBuilder.getCommands());
            playerRef.getPacketHandler().writeNoCache(hudPacket);

            // Debug: log position updates occasionally
            if (Math.random() < 0.05) { // Log ~5% of updates
                System.out.println("[SpeechBubbles] UI position update sent: (" + screenX + "," + screenY + ")");
            }

        } catch (Exception e) {
            System.err.println("[SpeechBubbles] Error updating position: " + e.getMessage());
        }
    }

    /**
     * Clear a bubble from screen (hide it).
     */
    private void clearBubbleAtPosition(@Nonnull PlayerRef playerRef, @Nonnull SpeechBubble bubble) {
        try {
            CustomHud hudPacket = new CustomHud(
                    true, // Clear
                    new UICommandBuilder().getCommands());

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
                .backgroundOpacity(config.getDefaultBackgroundOpacity())
                .fov(config.getFov());
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
            Universe universe = Universe.get();
            if (universe == null) {
                System.out.println("[SpeechBubbles] getEntityPosition: Universe is null");
                return null;
            }
            
            java.util.Collection<World> worlds = universe.getWorlds().values();
            if (worlds.isEmpty()) {
                System.out.println("[SpeechBubbles] getEntityPosition: No worlds available");
                return null;
            }
            
            // Try to find entity in all worlds
            for (World world : worlds) {
                EntityStore entityStore = world.getEntityStore();
                if (entityStore != null) {
                    Ref<EntityStore> entityRef = entityStore.getRefFromUUID(entityUuid);
                    if (entityRef != null && entityRef.isValid()) {
                        Store<EntityStore> store = entityRef.getStore();
                        TransformComponent transform = store.getComponent(entityRef,
                                TransformComponent.getComponentType());
                        if (transform != null) {
                            return transform.getPosition();
                        }
                    }
                }
            }
            System.out.println("[SpeechBubbles] getEntityPosition: Entity " + entityUuid + " not found in any world");
        } catch (Exception e) {
            System.err.println("[SpeechBubbles] getEntityPosition error: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Project 3D world position to 2D screen coordinates.
     * Takes into account player camera rotation and handles off-screen entities.
     * 
     * @param entityPos    Entity position in world
     * @param playerRef    Player reference for camera position
     * @param bubbleWidth  The bubble width in pixels
     * @param bubbleHeight The bubble height in pixels
     * @param tailTipX     The tail tip X offset
     * @param tailTipY     The tail tip Y offset
     * @param fov          Field of view in degrees
     * @return Array [screenX, screenY] or null if too far away
     */
    @Nullable
    private int[] project3DToScreen(@Nonnull Vector3d entityPos, @Nonnull PlayerRef playerRef,
            int bubbleWidth, int bubbleHeight, int tailTipX, int tailTipY, float fov) {
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
            float playerYaw = transform.getRotation().getYaw(); // Already in radians, can be unbounded
            float playerPitch = transform.getRotation().getPitch(); // Already in radians, can be unbounded

            // Normalize angles to [-π, π] range to prevent precision issues
            playerYaw = normalizeAngle(playerYaw);
            playerPitch = normalizeAngle(playerPitch);

            // Calculate relative position (entity - player)
            double dx = entityPos.getX() - playerPos.getX();
            double dy = entityPos.getY() - playerPos.getY() + 2.0; // Offset above entity head
            double dz = entityPos.getZ() - playerPos.getZ();

            // Distance check - don't show if too far
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > 2500) { // 50 blocks
                return null;
            }

            // Screen dimensions
            final int SCREEN_WIDTH = 1920;
            final int SCREEN_HEIGHT = 1080;
            final int SCREEN_CENTER_X = SCREEN_WIDTH / 2;
            final int SCREEN_CENTER_Y = SCREEN_HEIGHT / 2;

            // Use Hytale's Matrix4d to build view matrix
            // The view matrix transforms world coordinates to camera space
            Matrix4d viewMatrix = new Matrix4d();
            viewMatrix.identity();
            
            // Apply camera rotations
            // Hytale uses Y-up, yaw rotates around Y, pitch rotates around X
            // Note: playerYaw and playerPitch are already in radians, use directly
            Matrix4d rotTemp = new Matrix4d();
            viewMatrix.rotateAxis(playerYaw, 0, 1, 0, rotTemp);   // Yaw around Y
            viewMatrix.rotateAxis(playerPitch, 1, 0, 0, rotTemp); // Pitch around X
            
            // Transform relative position through view matrix
            // multiplyDirection applies rotation only (no translation needed since we have relative pos)
            Vector3d camSpace = new Vector3d(dx, dy, dz);
            viewMatrix.multiplyDirection(camSpace);
            
            // In camera space: -Z is forward (into screen), +X is right, +Y is up
            // So if camSpace.z > 0, entity is BEHIND camera
            if (camSpace.getZ() > -0.01) {
                return null; // Behind camera
            }
            
            // Perspective projection
            double fovRad = Math.toRadians(fov);
            double focalLength = (SCREEN_HEIGHT / 2.0) / Math.tan(fovRad / 2.0);
            
            // Project: X and Y are scaled by focalLength / distance
            // Distance is abs(Z) since camera looks down -Z
            double distance = Math.abs(camSpace.getZ());
            double scale = focalLength / Math.max(distance, 0.1);
            
            // Calculate screen coordinates
            int rawScreenX = SCREEN_CENTER_X + (int) (camSpace.getX() * scale);
            int rawScreenY = SCREEN_CENTER_Y - (int) (camSpace.getY() * scale); // Y inverted
            
            // Apply tail offset
            int screenX = rawScreenX - tailTipX;
            int screenY = rawScreenY - tailTipY;
            
            // Debug output
            System.out.println(String.format(
                "[SpeechBubbles] Yaw=%.1f Pitch=%.1f | Cam=(%.2f,%.2f,%.2f) | Scale=%.1f | Screen=(%d,%d)",
                playerYaw, playerPitch, camSpace.getX(), camSpace.getY(), camSpace.getZ(), scale, screenX, screenY));

            // Clamp to ensure at least half bubble is visible on screen
            int minVisibleX = -(bubbleWidth / 2);
            int maxVisibleX = SCREEN_WIDTH - (bubbleWidth / 2);
            int minVisibleY = -(bubbleHeight / 2);
            int maxVisibleY = SCREEN_HEIGHT - (bubbleHeight / 2);

            int clampedX = Math.max(minVisibleX, Math.min(maxVisibleX, screenX));
            int clampedY = Math.max(minVisibleY, Math.min(maxVisibleY, screenY));

            return new int[] { clampedX, clampedY };

        } catch (Exception e) {
            System.err.println("[SpeechBubbles] Error in project3DToScreen: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Normalizes an angle to the range [-π, π].
     * This handles accumulated angles from continuous rotation (e.g., yaw > 2π).
     * 
     * @param angle Angle in radians (can be any value)
     * @return Normalized angle in range [-π, π]
     */
    private float normalizeAngle(float angle) {
        float twoPi = (float) (2.0 * Math.PI);
        // First wrap to [0, 2π)
        angle = angle % twoPi;
        // Then adjust to [-π, π]
        if (angle > Math.PI) {
            angle -= twoPi;
        } else if (angle < -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }
}
