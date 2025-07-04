# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android Accessibility Inspector Service - exposes Android accessibility tree data through a WebSocket server for external inspection and automation tools. The service captures accessibility node information, screenshots, and enables remote UI automation through gestures and actions.

**Security Warning**: This service exposes all screen content through WebSocket. Disable when not in use.

## Build Commands

```bash
# Build the app
./gradlew build

# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug build to connected device
./gradlew installDebug

# Run tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

## Architecture

### Core Components

- **AccessibilityInspector** (`AccessibilityInspector.java`): Main accessibility service that captures UI tree data, handles screenshot capture, and processes automation commands
- **SocketService** (`SocketService.java`): WebSocket server running on port 38301 that handles client connections and message routing
- **ServiceActivity** (`ServiceActivity.java`): Simple launcher activity

### Data Flow

1. **Capture Process**: Client sends `{"message":"capture"}` → SocketService broadcasts intent → AccessibilityInspector captures tree + screenshot → Data compressed with GZIP → Sent to all connected WebSocket clients

2. **Action Process**: Client sends action command → SocketService directly calls AccessibilityInspector methods → Result sent back through WebSocket

### Key Features

- **Tree Capture**: Captures accessibility node tree with/without non-important views
- **Screenshot Integration**: Base64 encoded screenshots bundled with tree data
- **Accessibility Event Forwarding**: Real-time forwarding of user interactions with "before" tree context
- **UI Automation**: 
  - Element actions (click, focus, text input) via resourceId or hashCode
  - Gesture automation (tap, swipe, scroll) via coordinates
  - Activity launching with multiple launch types
- **Real-time Communication**: WebSocket server with GZIP compression

### Dependencies

- **AndroidAsync**: WebSocket server implementation
- **Google Accessibility Utils**: Tree traversal and node manipulation utilities
- **Auto-Value**: Value class generation
- **Guava**: Utility collections and functions

## WebSocket API

**Connection**: `ws://localhost:38301/` (use `adb forward tcp:38301 tcp:38301`)

## Message Types

The service supports two distinct message flows:

### 1. Client-Initiated Messages
**Flow**: Client sends command → Service responds

#### Commands
```json
{"message":"capture"}           // Request tree (important views only)
{"message":"captureNotImportant"}  // Request tree (all views)
{"message":"ping"}              // Connection test
{"message":"performAction", "resourceId":"...", "action":"CLICK"}
{"message":"performGesture", "gestureType":"TAP", "x":100, "y":200}
{"message":"launchActivity", "launchType":"PACKAGE", "packageName":"com.example.app"}
```

#### Response Messages

**Tree Data Response:**
```json
{
  "type": "tree",
  "children": [...]  // TreeDebug format
}
```

*Note: This format change has been tested for backward compatibility with the accompanying Inspector App.*

**Action/Gesture/Launch Results:**
```json
{
  "type": "actionResult|gestureResult|launchResult",
  "success": true,
  "message": "Description"
}
```

**Ping Response:**
```json
{"message": "pong"}
```

### 2. AccessibilityEvent-Initiated Messages
**Flow**: User interacts with device → Android generates AccessibilityEvent → Service automatically broadcasts to all clients

#### Event Messages
```json
{
  "type": "accessibilityEvent",
  "eventType": "VIEW_CLICKED|VIEW_SELECTED|VIEW_FOCUSED|SCROLL_SEQUENCE_END|TEXT_SEQUENCE_END|WINDOW_STATE_CHANGED",
  "timestamp": 1751544001234,
  "packageName": "com.example.app", 
  "className": "android.widget.Button",
  "source": {...},
  "metadata": {"treeAge": 1500}  // Only when before-tree follows
}
```

#### Before-Event Tree Messages  
For certain events (clicks, selections, focus, scrolls), a tree message follows showing UI state before the user action:
```json
{
  "type": "treeBeforeEvent",
  "eventType": "VIEW_CLICKED", 
  "timestamp": 1751544000734,
  "children": [...]  // UI state when user decided to act
}
```

**How Before-Event Trees are Obtained:**
The service continuously monitors for UI changes via `WINDOW_CONTENT_CHANGED` events. After 1 second of UI stability (no content changes), it captures and caches a "stable" tree. When user interaction events occur (clicks, selections, etc.), the service sends this cached stable tree as the "before" state, representing what the user saw when deciding to act. The `treeAge` in the event metadata indicates how long ago this stable tree was captured.

#### Error Messages
```json
{
  "type": "error",
  "message": "Error description"
}
```

## Development Guidelines

### Accessibility Service Configuration

- Service flags configured in `onServiceConnected()` - modify `allFlags` constant to adjust capture behavior
- Two capture modes: important views only vs all views (controlled by `hideNotImportant()`/`showNotImportant()`)

### WebSocket Message Handling

- All message parsing in `SocketRequestCallback.onConnected()`
- Direct method calls to AccessibilityInspector instance (stored statically)
- Error responses follow `{"type":"[messageType]Result", "success":false, "message":"..."}`

### Tree Capture Process

- Uses `TreeDebug.logNodeTrees()` from Google accessibility utils
- GZIP compression for large payloads
- Screenshot capture integrated with tree data

### Adding New Commands

1. Add JSON parsing in `SocketService.SocketRequestCallback`
2. Add method implementation in `AccessibilityInspector`
3. Follow result pattern: `send[ActionType]Result(boolean, String)`

## API Levels & Compatibility

- **Min SDK**: 28 (Android 9)
- **Target SDK**: 31 (Android 12)
- **Gesture Support**: Requires API 24+ (checked at runtime)
- **Java Version**: 11

## Known Issues

- WebSocket server can become unresponsive; may require service restart or device reboot
- Null pointer exceptions possible during active screen updates
- Service process may not terminate properly when accessibility service is disabled
- Samsung Phone app doesn't generate scroll events (app-specific limitation)
- Modern apps typically don't provide scroll delta data (`totalScrollX/Y` are often 0)
- VIEW_SELECTED events are rare in modern apps (most use clicks instead)

## Scroll Event Behavior Patterns

**User-initiated vs App-initiated Scrolling:**
- **User scrolls**: Multiple timestamps in `scrollTimestamps` array (continuous gesture)
- **App scrolls**: Single timestamp (programmatic animation, like ViewPager transitions)

**Scroll Direction Detection:**
- **Horizontal scrolls**: May have `totalScrollX ≠ 0` (ViewPager page changes)
- **Vertical scrolls**: Usually `totalScrollX = 0, totalScrollY = 0` regardless of source
- **Note**: Scroll delta reliability varies by app implementation

**Examples:**
- Clicking ViewPager tab → Single timestamp + horizontal scroll values
- User swiping list → Multiple timestamps + zero scroll values
- App page transitions → Single timestamp + variable scroll values

## Development Tools

### Debug Clients

**debug_client.py**: Primary debugging tool that shows detailed message analysis
```bash
python3 debug_client.py
```
- Displays message type in header (🎯 ACCESSIBILITY EVENT, 🌳 TREE MESSAGE, etc.)
- Shows JSON preview (truncated at 1000 characters)
- Handles both string and byte messages

**quick_test.py**: Simple connectivity test for basic functionality
```bash
python3 quick_test.py
```
- Minimal output showing message types and sizes
- Good for quick connection verification
- Lighter output for basic testing

**Connection Setup:**
```bash
# Forward port from Android device
adb forward tcp:38301 tcp:38301

# Then run either debug client
python3 debug_client.py
```