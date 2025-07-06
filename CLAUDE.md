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

1. **Capture Process**: Client sends `{"message":"capture"}` → SocketService broadcasts intent → AccessibilityInspector captures tree using TreeDebug → JSON tree data sent to all connected WebSocket clients

2. **Action Process**: Client sends action command → SocketService directly calls AccessibilityInspector methods → Result sent back through WebSocket

### Key Features

- **Tree Capture**: Captures accessibility node tree with/without non-important views
- **Screenshot Integration**: Base64 encoded screenshots bundled with tree data
- **Accessibility Event Forwarding**: Real-time forwarding of user interactions with "before" tree context
- **UI Automation**: 
  - Element actions (click, focus, text input) via resourceId or hashCode
  - Gesture automation (tap, swipe, scroll) via coordinates
  - Activity launching with multiple launch types
- **Real-time Communication**: WebSocket server with JSON message format

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
{"message":"findByViewId", "viewId":"com.example.app:id/button"}
{"message":"findByText", "text":"Submit"}              // DEPRECATED - use customFindByText
{"message":"customFindByText", "text":"Submit"}        // Exact text match (case-sensitive)
{"message":"findByRegex", "pattern":".*Submit.*"}      // Regex pattern matching
{"message":"customFindByViewId", "viewId":"..."}       // Alternative viewId search
{"message":"findByProps", "properties":{"text":"Submit","isClickable":true}} // Property-based search
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

**Find Response (All Methods):**
```json
{
  "type": "findResult",
  "success": true,
  "viewId": "com.example.app:id/button",  // Only present for findByViewId
  "text": "Submit",                          // Only present for findByText/customFindByText
  "method": "customFindByText",           // Only present for custom methods
  "stats": "Window: Slack - Total nodes: 190, ...",  // Only present for customFindByText
  "count": 2,
  "nodes": [
    {
      "hashCode": 123456,
      "className": "android.widget.Button",
      "text": "Submit",
      "contentDescription": "",
      "viewIdResourceName": "com.example.app:id/button",
      "isClickable": true,
      "isEnabled": true,
      "isFocusable": true,
      "isFocused": false,
      "isScrollable": false,
      "isCheckable": false,
      "isChecked": false,
      "isSelected": false,
      "boundsInScreen": {"left": 100, "top": 200, "right": 300, "bottom": 250}
    }
  ]
}
```

**Response Field Guide:**
- `method`: Present only for custom methods (identifies which implementation was used)
- `stats`: Present only for `customFindByText` (provides tree analysis for debugging)
- `viewId`: Present for `findByViewId` and `customFindByViewId` commands
- `text`: Present for `findByText` and `customFindByText` commands
- `pattern`: Present for `findByRegex` commands
- `properties`: Present for `findByProps` commands

### **Text Search Methods Explained**

**`customFindByText`**: Exact, case-sensitive matching in BOTH text and contentDescription fields
```json
{"message": "customFindByText", "text": "Activity"}
// Searches both node.getText() and node.getContentDescription()
// Finds: "Activity" in either field ✅
// Misses: "activity", "ACTIVITY", "My Activity" ❌
```

**`findByRegex`**: Flexible pattern matching in BOTH text and contentDescription fields
```json
{"message": "findByRegex", "pattern": "(?i)activity"}     // Case-insensitive in either field
{"message": "findByRegex", "pattern": ".*[0-9]+.*"}       // Contains numbers in either field
{"message": "findByRegex", "pattern": "^[A-Z][a-z]+$"}    // Capitalized words in either field
{"message": "findByRegex", "pattern": "(btn|button)"}     // Multiple options in either field
// Searches both node.getText() and node.getContentDescription()
```

**`findByProps`**: Multi-property matching
```json
{"message": "findByProps", "properties": {"text": "Submit", "isClickable": true}}
{"message": "findByProps", "properties": {"viewIdResourceName": "com.Slack:id/button", "isEnabled": true}}
{"message": "findByProps", "properties": {"className": "Button", "text": "OK", "isClickable": true}}
```

**Supported Properties:**
- **String properties**: `text`, `contentDescription`, `className`, `viewIdResourceName`/`resourceId`/`viewId`
- **Boolean properties**: `isClickable`, `isEnabled`, `isFocusable`, `isFocused`, `isScrollable`, `isCheckable`, `isChecked`, `isSelected`
- **Integer properties**: `childCount`

**Note**: Found nodes use a **different format** than tree nodes. Find results include:
- Direct boolean properties (`isClickable`, `isEnabled`, etc.)
- `hashCode` for element identification
- Simplified structure for easier processing

Tree nodes use the TreeDebug format with nested metadata objects.

## **Find Method Comparison**

| Method | Type | Status | Use Case |
|--------|------|--------|----------|
| `findByViewId` | Native | ✅ **Recommended** | Finding elements by resource ID |
| `findByText` | Native | ❌ **Deprecated** | Use `customFindByText` instead |
| `customFindByText` | Custom | ✅ **Recommended** | Exact text match (case-sensitive) |
| `findByRegex` | Custom | ✅ **Recommended** | Pattern matching with regex |
| `findByProps` | Custom | ✅ **Recommended** | Multi-property search with JSON criteria |
| `customFindByViewId` | Custom | ✅ **Alternative** | Debugging viewId issues |

### **Why findByText is Deprecated**

Through extensive testing with comparison scripts, we discovered that Android's native `findAccessibilityNodeInfosByText()` method has significant limitations:

1. **Semantic Filtering**: Filters out navigation/UI labels while keeping "content" text
2. **Case Sensitivity**: Strictly case-sensitive, unlike our custom implementation  
3. **Missing Visible Elements**: Skips prominent UI elements like tab names ("Activity", "Later") while finding app names ("Slack")

**Test Results Example** (from `test_native_vs_custom.py`):
```
Searching for: 'Activity' (visible tab in Slack UI)
  Native method: 0 nodes found    ❌ Misses visible UI
  Custom method: 1 nodes found    ✅ Finds visible UI
```

### **Testing Methodology**

The deprecation decision was based on systematic testing using several diagnostic scripts:

1. **`test_native_vs_custom.py`**: Direct comparison showing native method missing visible UI elements
2. **`test_case_sensitivity.py`**: Ruled out case sensitivity as the sole issue
3. **`test_find_differences.py`**: Identified specific nodes missed by native method
4. **`test_viewid_consistency.py`**: Confirmed native `findByViewId` works correctly

**Key Finding**: Native `findByText` consistently missed 5-17 visible UI elements per search, while `findByViewId` showed perfect consistency between native and custom implementations.

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
- JSON format for tree data transmission
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

## Tree Capture Limitations

**System UI Filtering**: The `TreeDebug.logNodeTrees()` method intentionally filters out system UI elements from captured trees, including:

- **Status bar** (contains clock, battery, signal indicators, notifications)
- **Navigation bar** 
- Windows that are not `isActive()`
- Windows with pane titles "Status bar" or "Notification shade."

**Root Window Selection**: TreeDebug uses `getRootInActiveWindow()` instead of each window's actual root, which may miss content in non-active windows.

**Workaround**: Use `findByViewId` and `findByText` commands to access system UI elements that don't appear in tree captures:

```json
// These work for system UI elements:
{"message": "findByViewId", "viewId": "com.android.systemui:id/clock"}
{"message": "findByText", "text": "7:45"}

// These miss system UI elements:
{"message": "capture"}
{"message": "captureNotImportant"}
```

**Future Solution**: To capture system UI elements in trees, `TreeDebug.logNodeTrees()` would need modification to:
1. Remove status bar/navigation bar filtering (lines 104-105, 113 in TreeDebug.java)
2. Use each window's actual root instead of `getRootInActiveWindow()` (lines 76-77)
3. Include non-active windows if desired (lines 58-60)

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

### Test Scripts

**test_capture_commands.py**: Tests tree capture functionality
```bash
python3 test_capture_commands.py
```
- Tests `capture` (important nodes only) and `captureNotImportant` (all nodes)
- Shows JSON size, node counts, and response times
- Verifies WebSocket connection health with ping/pong

**test_simple_find.py**: Tests findByViewId and findByText commands
```bash
python3 test_simple_find.py
```
- Tests finding specific elements like system clock
- Shows detailed node properties for found elements
- No tree capture to avoid timeout issues

**test_find_interactive.py**: Interactive element search tool
```bash
python3 test_find_interactive.py
```
- Menu-driven interface for searching elements
- Supports findByViewId and findByText searches
- Shows all node properties including bounds, states, and IDs
- Filters out accessibility events automatically

**test_find_and_click.py**: Demonstrates find + action workflow
```bash
python3 test_find_and_click.py
```
- Searches for elements by text
- Allows selection from multiple results
- Performs click actions on selected elements
- Shows detailed properties for all found elements

**test_find_commands.py**: Comprehensive test of find functionality
```bash
python3 test_find_commands.py
```
- Automated test of both find commands
- Tests with system UI elements and common text
- Tests error cases (missing parameters)
- Verifies response formats

**test_actions.py**: Tests performAction commands
```bash
python3 test_actions.py
```
- Tests various action types (CLICK, FOCUS, LONG_CLICK, SET_TEXT)
- Uses found elements from findByText/findByViewId
- Tests both hashCode and resourceId targeting
- Tests error cases (missing parameters)

**test_gestures.py**: Tests performGesture commands
```bash
python3 test_gestures.py
```
- Tests all gesture types (TAP, SWIPE, SCROLL, LONG_PRESS, DOUBLE_TAP)
- Tests predefined scroll directions (UP, DOWN, LEFT, RIGHT)
- Tests optional parameters (duration, end coordinates)
- Tests error cases (missing gestureType, invalid coordinates)

**test_launch.py**: Tests launchActivity commands
```bash
python3 test_launch.py
```
- Tests various launch types (PACKAGE, ACTIVITY, INTENT)
- Tests common apps (Settings, Calculator, Browser)
- Tests different intent actions and data formats
- Tests error cases (missing parameters, invalid packages)

### **Diagnostic Scripts**

**test_native_vs_custom.py**: Compare native vs custom find methods
```bash
python3 test_native_vs_custom.py
```
- Direct performance and accuracy comparison
- Reveals native method limitations
- Used to identify findByText deprecation need

**test_case_sensitivity.py**: Test case sensitivity hypothesis
```bash
python3 test_case_sensitivity.py
```
- Tests various capitalizations of same text
- Rules out case sensitivity as sole issue
- Shows semantic filtering patterns

**test_find_differences.py**: Identify specific missed nodes
```bash
python3 test_find_differences.py
```
- Shows exact nodes found by custom but not native
- Analyzes patterns in missed content
- Demonstrates Android's semantic filtering

**test_viewid_consistency.py**: Verify viewId method reliability
```bash
python3 test_viewid_consistency.py
```
- Confirms native findByViewId works correctly
- Shows why only findByText needs deprecation
- Tests various viewId formats and edge cases

**debug_tree_format.py**: Examine tree structure
```bash
python3 debug_tree_format.py
```
- Shows actual tree format and field names
- Helps debug tree vs find result differences
- Useful for understanding data structures

**Connection Setup:**
```bash
# Forward port from Android device
adb forward tcp:38301 tcp:38301

# Then run any test script
python3 test_script_name.py
```