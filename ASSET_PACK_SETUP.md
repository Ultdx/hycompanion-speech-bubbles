# Speech Bubbles Asset Pack Setup

## The Problem

Hytale's AssetModule loads asset packs at server startup, BEFORE plugins are initialized. This means the UI assets (the .ui file) must be extracted from the JAR and placed in the `mods/` folder as a separate asset pack.

## Solution

You need to extract the asset pack files from the JAR to a folder in your mods directory.

## Manual Setup

### Step 1: Build the Plugin

```batch
compile-plugin.bat
```

### Step 2: Extract the Asset Pack

Create a folder named `dev.hycompanion.speech_SpeechBubbles` in your `mods/` folder and extract the asset files:

```batch
# Navigate to your mods folder
cd C:\Path\To\HytaleServer\mods

# Create the asset pack folder
mkdir dev.hycompanion.speech_SpeechBubbles

# Extract from JAR (adjust path as needed)
cd dev.hycompanion.speech_SpeechBubbles
jar xf "..\hycompanion-speech-bubbles.jar" manifest.json Common/
```

### Step 3: Verify Structure

Your folder structure should look like:

```
mods/
  hycompanion-speech-bubbles.jar
  dev.hycompanion.speech_SpeechBubbles/
    manifest.json
    Common/
      UI/
        Custom/
          SpeechBubble.ui
```

### Step 4: Restart Server

The AssetModule only loads asset packs at startup, so you must restart the server after extracting the assets.

## Alternative: Using the Extract Script

Run the provided script:

```batch
extract-assets.bat "C:\Path\To\HytaleServer\mods"
```

## Troubleshooting

### "Failed to load CustomUI documents"

This means the asset pack is not properly extracted. Check:

1. The folder `mods/dev.hycompanion.speech_SpeechBubbles/` exists
2. It contains `manifest.json` and `Common/UI/Custom/SpeechBubble.ui`
3. You restarted the server after extracting

### "Skipping pack at dev.hycompanion.speech_SpeechBubbles: missing or invalid manifest.json"

The manifest.json is missing or corrupted. Re-extract it from the JAR.

### Asset pack not loading

Check server logs for:
```
[AssetModule|P] Loaded pack: dev.hycompanion.speech:SpeechBubbles from dev.hycompanion.speech_SpeechBubbles
```

If you don't see this, the asset pack folder is not in the right location.
