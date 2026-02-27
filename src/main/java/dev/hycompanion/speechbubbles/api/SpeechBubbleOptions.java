package dev.hycompanion.speechbubbles.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration options for a speech bubble.
 * 
 * This class uses the builder pattern for easy configuration:
 * <pre>
 * SpeechBubbleOptions options = new SpeechBubbleOptions()
 *     .duration(8000)
 *     .maxWidth(300)
 *     .maxHeight(200)
 *     .textColor("#FFD700");
 * </pre>
 */
public final class SpeechBubbleOptions {
    
    // Default values
    public static final long DEFAULT_DURATION = 5000; // 5 seconds
    public static final int DEFAULT_MAX_WIDTH = 250;   // pixels
    public static final int DEFAULT_MAX_HEIGHT = 150;  // pixels
    public static final String DEFAULT_TEXT_COLOR = "#FFFFFF";
    public static final float DEFAULT_BACKGROUND_OPACITY = 0.9f;

    private Long duration;
    private Integer maxWidth;
    private Integer maxHeight;
    private String textColor;
    private Float backgroundOpacity;

    /**
     * Create new options with all defaults.
     */
    public SpeechBubbleOptions() {
    }

    /**
     * Set the display duration.
     * 
     * @param durationMs Duration in milliseconds (minimum 1000ms)
     * @return this for chaining
     */
    @Nonnull
    public SpeechBubbleOptions duration(long durationMs) {
        this.duration = Math.max(durationMs, 1000);
        return this;
    }

    /**
     * Set the maximum width of the bubble.
     * 
     * @param width Width in pixels (100-500)
     * @return this for chaining
     */
    @Nonnull
    public SpeechBubbleOptions maxWidth(int width) {
        this.maxWidth = Math.clamp(width, 100, 500);
        return this;
    }

    /**
     * Set the maximum height of the bubble.
     * 
     * @param height Height in pixels (50-300)
     * @return this for chaining
     */
    @Nonnull
    public SpeechBubbleOptions maxHeight(int height) {
        this.maxHeight = Math.clamp(height, 50, 300);
        return this;
    }

    /**
     * Set the text color.
     * 
     * @param color Hex color code (e.g., "#FFFFFF" or "#FFD700")
     * @return this for chaining
     */
    @Nonnull
    public SpeechBubbleOptions textColor(@Nonnull String color) {
        this.textColor = color;
        return this;
    }

    /**
     * Set the background opacity.
     * 
     * @param opacity Opacity from 0.0 (fully transparent) to 1.0 (fully opaque)
     * @return this for chaining
     */
    @Nonnull
    public SpeechBubbleOptions backgroundOpacity(float opacity) {
        this.backgroundOpacity = Math.clamp(opacity, 0.0f, 1.0f);
        return this;
    }

    // ========== Getters ==========

    public long getDuration() {
        return duration != null ? duration : DEFAULT_DURATION;
    }

    public int getMaxWidth() {
        return maxWidth != null ? maxWidth : DEFAULT_MAX_WIDTH;
    }

    public int getMaxHeight() {
        return maxHeight != null ? maxHeight : DEFAULT_MAX_HEIGHT;
    }

    @Nonnull
    public String getTextColor() {
        return textColor != null ? textColor : DEFAULT_TEXT_COLOR;
    }

    public float getBackgroundOpacity() {
        return backgroundOpacity != null ? backgroundOpacity : DEFAULT_BACKGROUND_OPACITY;
    }

    /**
     * Check if any custom options were set.
     */
    public boolean hasCustomOptions() {
        return duration != null || maxWidth != null || maxHeight != null 
            || textColor != null || backgroundOpacity != null;
    }

    /**
     * Merge with another options object, preferring this object's values.
     */
    @Nonnull
    public SpeechBubbleOptions merge(@Nullable SpeechBubbleOptions other) {
        if (other == null) {
            return this;
        }
        
        SpeechBubbleOptions merged = new SpeechBubbleOptions();
        merged.duration = this.duration != null ? this.duration : other.duration;
        merged.maxWidth = this.maxWidth != null ? this.maxWidth : other.maxWidth;
        merged.maxHeight = this.maxHeight != null ? this.maxHeight : other.maxHeight;
        merged.textColor = this.textColor != null ? this.textColor : other.textColor;
        merged.backgroundOpacity = this.backgroundOpacity != null ? this.backgroundOpacity : other.backgroundOpacity;
        
        return merged;
    }
}
