// app/src/main/java/com/jwlilly/accessibilityinspector/AccessibilityInspector.java
package com.jwlilly.accessibilityinspector;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.accessibility.utils.TreeDebug;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.Executor;


public class AccessibilityInspector extends AccessibilityService implements Observer {
    private final String LOG_TAG = "AccessibilityInspector";
    private AccessibilityListener captureListener;

    private AccessibilityListener importantListener;
    private AccessibilityListener actionListener;
    public AccessibilityInspector _this = this;
    private JSONObject jsonObject;


    private final int allFlags = AccessibilityServiceInfo.DEFAULT
        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        | AccessibilityServiceInfo.FEEDBACK_GENERIC
        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        | AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

    // Scroll aggregation state
    private Handler scrollHandler = new Handler(Looper.getMainLooper());
    private Runnable scrollEndRunnable = null;
    private static final int SCROLL_END_DELAY = 300; // ms
    private int totalScrollX = 0;
    private int totalScrollY = 0;
    private int scrollEventCount = 0;
    private List<Long> scrollTimestamps = new ArrayList<>();
    private AccessibilityEvent firstScrollEvent = null;

    // Text input session state
    private Handler textHandler = new Handler(Looper.getMainLooper());
    private Runnable textEndRunnable = null;
    private static final int TEXT_INPUT_TIMEOUT = 2000; // 2 seconds of inactivity
    private StringBuilder sessionText = new StringBuilder();
    private int textEventCount = 0;
    private long firstTextTime = 0;
    private AccessibilityNodeInfo activeTextField = null;
    private int currentTextFieldHashCode = -1;
    private JSONObject textFieldTree = null;
    private int pasteEventCount = 0;

    // UI stability detection for stable tree capture
    private Handler stabilityHandler = new Handler(Looper.getMainLooper());
    private Runnable captureStableTree = null;
    private static final int UI_STABILITY_DELAY = 1000; // 1 second of no UI changes
    private JSONObject stableUITree = null;
    private long stableTreeTimestamp = 0;
    
    // Debug flag to send WINDOW_CONTENT_CHANGED events to clients
    private static final boolean SEND_WINDOW_CONTENT_CHANGED_EVENTS = false;
    
    // Flag to control visible-only filtering for manual tree captures
    private boolean manualCaptureVisibleOnly = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            int eventType = event.getEventType();
            
            // Log only non-noisy events
            if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                Log.d(LOG_TAG, "Event: " + eventType + " from " + event.getPackageName());
                if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    Log.d(LOG_TAG, "SCROLL EVENT RECEIVED!");
                }
                if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
                    Log.d(LOG_TAG, "SELECTION EVENT RECEIVED!");
                }
                // Log all events from Contacts to debug
                if (event.getPackageName() != null && event.getPackageName().toString().contains("contact")) {
                    Log.d(LOG_TAG, "CONTACTS EVENT: " + eventType + " (" + getEventTypeName(eventType) + ")");
                }
            }
            
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                Log.d(LOG_TAG, "WINDOW_CONTENT_CHANGED from package: " + event.getPackageName());
                handleUIContentChange(event);
                // Optionally send to clients for debugging
                if (SEND_WINDOW_CONTENT_CHANGED_EVENTS) {
                    sendAccessibilityEvent(event);
                }
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                handleScrollEvent(event);
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                handleTextChangedEvent(event);
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                handleFocusEvent(event);
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                handleClickEvent(event);
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
                handleSelectionEvent(event);
            } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleWindowStateEvent(event);
            } else {
                // Send all other events without tree capture
                sendAccessibilityEvent(event);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error processing accessibility event: " + e.getMessage(), e);
        }
    }

    @Override
    public void onInterrupt() {

    }

    @Override
    public void onDestroy() {
        Log.d("ServerSocket", "stopping server");
        // Clear the instance reference
        SocketService.setAccessibilityServiceInstance(null);
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        // Set the instance reference for direct calls
        SocketService.setAccessibilityServiceInstance(this);
        Log.d(LOG_TAG, "AccessibilityInspector connected and instance set");

        captureListener = new AccessibilityListener();
        registerReceiver(captureListener, new IntentFilter("A11yInspector"));
        importantListener = new AccessibilityListener();
        registerReceiver(importantListener, new IntentFilter("A11yInspectorImportant"));
        actionListener = new AccessibilityListener();
        registerReceiver(actionListener, new IntentFilter("A11yInspectorAction"));
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.notificationTimeout = 100;
        info.flags =
                AccessibilityServiceInfo.DEFAULT
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FEEDBACK_GENERIC
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
                | AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES; // This line added during debugging, but doesn't seem critical.
        info.eventTypes = AccessibilityEvent.TYPE_ANNOUNCEMENT
                | AccessibilityEvent.TYPE_VIEW_CLICKED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_VIEW_SELECTED
                | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED; // Needed for UI stability detection
        this.setServiceInfo(info);
    }

    public Context getContext() {
        return this.getApplicationContext();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterReceiver(captureListener);
        unregisterReceiver(importantListener);
        unregisterReceiver(actionListener);
        return super.onUnbind(intent);
    }

    @Override
    public void update(Observable observable, Object o) {

    }

    private class AccessibilityListener extends BroadcastReceiver {
        @RequiresApi(api = Build.VERSION_CODES.R)
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "Broadcast received with action: " + intent.getAction());

            if(intent.getAction().equalsIgnoreCase("A11yInspector")) {
                boolean visibleOnly = intent.getBooleanExtra("visibleOnly", false);
                Log.d(LOG_TAG, "Processing capture request (important only), visibleOnly=" + visibleOnly);
                manualCaptureVisibleOnly = visibleOnly;
                hideNotImportant();
                startCapture();
            } else if(intent.getAction().equalsIgnoreCase("A11yInspectorImportant")) {
                boolean visibleOnly = intent.getBooleanExtra("visibleOnly", false);
                Log.d(LOG_TAG, "Processing capture request (all), visibleOnly=" + visibleOnly);
                manualCaptureVisibleOnly = visibleOnly;
                showNotImportant();
                startCapture();
            } else if(intent.getAction().equalsIgnoreCase("A11yInspectorAction")) {
                Log.d(LOG_TAG, "Processing action request");
                // Handle action requests - support both resourceId and hashCode
                String resourceId = intent.getStringExtra("resourceId");
                String hashCodeStr = intent.getStringExtra("hashCode");
                String action = intent.getStringExtra("action");
                String text = intent.getStringExtra("text");
                performAction(resourceId, hashCodeStr, action, text);
            } else {
                Log.w(LOG_TAG, "Unknown broadcast action: " + intent.getAction());
            }
        }
    }

    // Method to launch activities - now called directly
    public void launchActivity(String launchType, String packageName, String className,
                               String intentAction, String data, String category, String extrasJson) {
        Log.d(LOG_TAG, "launchActivity called with type: " + launchType + ", package: " + packageName);

        try {
            Intent launchIntent = null;
            String launchDescription = "";

            switch (launchType.toUpperCase()) {
                case "PACKAGE":
                    // Launch app by package name (main launcher activity)
                    if (packageName == null || packageName.isEmpty()) {
                        sendLaunchResult(false, "Package name is required for PACKAGE launch type");
                        return;
                    }
                    launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        sendLaunchResult(false, "No launch intent found for package: " + packageName);
                        return;
                    }
                    launchDescription = "package '" + packageName + "'";
                    break;

                case "COMPONENT":
                    // Launch specific activity by package and class name
                    if (packageName == null || packageName.isEmpty() || className == null || className.isEmpty()) {
                        sendLaunchResult(false, "Both package name and class name are required for COMPONENT launch type");
                        return;
                    }
                    launchIntent = new Intent();
                    launchIntent.setComponent(new ComponentName(packageName, className));
                    launchDescription = "component '" + packageName + "/" + className + "'";
                    break;

                case "INTENT":
                    // Launch with custom intent
                    if (intentAction == null || intentAction.isEmpty()) {
                        sendLaunchResult(false, "Intent action is required for INTENT launch type");
                        return;
                    }
                    launchIntent = new Intent(intentAction);
                    launchDescription = "intent action '" + intentAction + "'";

                    // Add package targeting if provided
                    if (packageName != null && !packageName.isEmpty()) {
                        launchIntent.setPackage(packageName);
                        launchDescription += " targeting package '" + packageName + "'";
                    }

                    // Add data URI if provided
                    if (data != null && !data.isEmpty()) {
                        launchIntent.setData(Uri.parse(data));
                        launchDescription += " with data '" + data + "'";
                    }

                    // Add category if provided
                    if (category != null && !category.isEmpty()) {
                        launchIntent.addCategory(category);
                        launchDescription += " and category '" + category + "'";
                    }
                    break;

                case "URL":
                    // Launch URL in browser or appropriate app
                    if (data == null || data.isEmpty()) {
                        sendLaunchResult(false, "URL is required for URL launch type (use 'data' field)");
                        return;
                    }
                    launchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(data));
                    launchDescription = "URL '" + data + "'";
                    break;

                case "SETTINGS":
                    // Launch specific settings screen
                    String settingsAction = intentAction != null ? intentAction : android.provider.Settings.ACTION_SETTINGS;
                    launchIntent = new Intent(settingsAction);
                    launchDescription = "settings screen '" + settingsAction + "'";
                    break;

                case "DIAL":
                    // Launch dialer with phone number
                    if (data == null || data.isEmpty()) {
                        sendLaunchResult(false, "Phone number is required for DIAL launch type (use 'data' field)");
                        return;
                    }
                    launchIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + data));
                    launchDescription = "dialer with number '" + data + "'";
                    break;

                case "SMS":
                    // Launch SMS with phone number
                    if (data == null || data.isEmpty()) {
                        sendLaunchResult(false, "Phone number is required for SMS launch type (use 'data' field)");
                        return;
                    }
                    launchIntent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + data));
                    launchDescription = "SMS to '" + data + "'";
                    break;

                case "EMAIL":
                    // Launch email client
                    launchIntent = new Intent(Intent.ACTION_SEND);
                    launchIntent.setType("text/plain");
                    if (data != null && !data.isEmpty()) {
                        launchIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{data});
                        launchDescription = "email to '" + data + "'";
                    } else {
                        launchDescription = "email client";
                    }
                    break;

                default:
                    sendLaunchResult(false, "Unknown launch type: " + launchType);
                    return;
            }

            if (launchIntent == null) {
                sendLaunchResult(false, "Failed to create launch intent for " + launchDescription);
                return;
            }

            // Add extras if provided
            if (extrasJson != null && !extrasJson.isEmpty()) {
                try {
                    JSONObject extras = new JSONObject(extrasJson);
                    addExtrasToIntent(launchIntent, extras);
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to parse extras JSON: " + e.getMessage());
                    // Continue without extras rather than failing
                }
            }

            // Set flags for launching from accessibility service
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Check if the intent can be resolved (skip for COMPONENT type as it might not be in manifest)
            if (!launchType.equalsIgnoreCase("COMPONENT")) {
                PackageManager pm = getPackageManager();
                List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launchIntent, 0);

                if (resolveInfos.isEmpty()) {
                    Log.w(LOG_TAG, "No apps found to handle intent, but attempting launch anyway");
                }
            }

            // Launch the activity
            Log.d(LOG_TAG, "Attempting to start activity: " + launchDescription);
            startActivity(launchIntent);
            sendLaunchResult(true, "Successfully launched " + launchDescription);
            Log.d(LOG_TAG, "Successfully launched " + launchDescription);

        } catch (SecurityException e) {
            String errorMessage = "Security exception launching activity: " + e.getMessage();
            sendLaunchResult(false, errorMessage);
            Log.e(LOG_TAG, errorMessage, e);
        } catch (Exception e) {
            String errorMessage = "Error launching activity: " + e.getMessage();
            sendLaunchResult(false, errorMessage);
            Log.e(LOG_TAG, errorMessage, e);
        }
    }

    // Helper method to add extras to intent from JSON
    private void addExtrasToIntent(Intent intent, JSONObject extrasJson) {
        try {
            java.util.Iterator<String> keys = extrasJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = extrasJson.get(key);

                if (value instanceof String) {
                    intent.putExtra(key, (String) value);
                } else if (value instanceof Integer) {
                    intent.putExtra(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    intent.putExtra(key, (Boolean) value);
                } else if (value instanceof Double) {
                    intent.putExtra(key, (Double) value);
                } else if (value instanceof Long) {
                    intent.putExtra(key, (Long) value);
                } else {
                    // Convert other types to string
                    intent.putExtra(key, value.toString());
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Error adding extras to intent: " + e.getMessage());
        }
    }

    // Send launch result back to the client
    public void sendLaunchResult(boolean success, String message) {
        Log.d(LOG_TAG, "sendLaunchResult called: " + success + " - " + message);
        try {
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "launchResult");
            resultJson.put("success", success);
            resultJson.put("message", message);

            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            Log.d(LOG_TAG, "Launch result sent: " + message);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending launch result: " + e.getMessage());
        }
    }

    // Updated method to perform actions on UI elements using resourceId or hashCode
    public void performAction(String resourceId, String hashCodeStr, String actionType, String text) {
        try {
            AccessibilityNodeInfo targetNode = null;
            String searchCriteria = "";

            // Try to find node by resourceId first, then by hashCode
            if (resourceId != null && !resourceId.isEmpty()) {
                targetNode = findNodeByResourceId(resourceId);
                searchCriteria = "resource ID '" + resourceId + "'";
            } else if (hashCodeStr != null && !hashCodeStr.isEmpty()) {
                try {
                    int hashCode = Integer.parseInt(hashCodeStr);
                    targetNode = findNodeByHashCode(hashCode);
                    searchCriteria = "hash code '" + hashCodeStr + "'";
                } catch (NumberFormatException e) {
                    sendActionResult(false, "Invalid hash code format: " + hashCodeStr);
                    return;
                }
            }

            if (targetNode == null) {
                String errorMsg = searchCriteria.isEmpty() ?
                        "No search criteria provided (resourceId or hashCode required)" :
                        "Node with " + searchCriteria + " not found";
                sendActionResult(false, errorMsg);
                return;
            }

            boolean result = false;
            String message = "";

            switch (actionType.toUpperCase()) {
                case "ACTION_CLICK":
                case "CLICK":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    message = result ? "Click action performed successfully" : "Click action failed";
                    break;

                case "ACTION_FOCUS":
                case "FOCUS":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    message = result ? "Focus action performed successfully" : "Focus action failed";
                    break;

                case "ACTION_SET_TEXT":
                case "SET_TEXT":
                    if (text != null) {
                        Bundle arguments = new Bundle();
                        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                        message = result ? "Set text action performed successfully" : "Set text action failed";
                    } else {
                        message = "Text parameter is required for SET_TEXT action";
                    }
                    break;

                case "ACTION_CLEAR_TEXT":
                case "CLEAR_TEXT":
                    Bundle clearArgs = new Bundle();
                    clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
                    message = result ? "Clear text action performed successfully" : "Clear text action failed";
                    break;

                case "ACTION_LONG_CLICK":
                case "LONG_CLICK":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                    message = result ? "Long click action performed successfully" : "Long click action failed";
                    break;

                case "ACTION_SCROLL_FORWARD":
                case "SCROLL_FORWARD":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    message = result ? "Scroll forward action performed successfully" : "Scroll forward action failed";
                    break;

                case "ACTION_SCROLL_BACKWARD":
                case "SCROLL_BACKWARD":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    message = result ? "Scroll backward action performed successfully" : "Scroll backward action failed";
                    break;

                case "ACTION_ACCESSIBILITY_FOCUS":
                case "ACCESSIBILITY_FOCUS":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    message = result ? "Accessibility focus action performed successfully" : "Accessibility focus action failed";
                    break;

                case "ACTION_CLEAR_ACCESSIBILITY_FOCUS":
                case "CLEAR_ACCESSIBILITY_FOCUS":
                    result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
                    message = result ? "Clear accessibility focus action performed successfully" : "Clear accessibility focus action failed";
                    break;

                default:
                    message = "Unknown action type: " + actionType;
                    break;
            }

            // Add search criteria info to the message
            if (result) {
                message += " (target found by " + searchCriteria + ")";
            }

            sendActionResult(result, message);
            Log.d(LOG_TAG, message);

        } catch (Exception e) {
            String errorMessage = "Error performing action: " + e.getMessage();
            sendActionResult(false, errorMessage);
            Log.e(LOG_TAG, errorMessage, e);
        }
    }

    // Helper method to find a node by its resource ID
    private AccessibilityNodeInfo findNodeByResourceId(String resourceId) {
        List<AccessibilityWindowInfo> windows = getWindows();

        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo rootNode = window.getRoot();
            if (rootNode != null) {
                AccessibilityNodeInfo foundNode = findNodeByResourceIdRecursive(rootNode, resourceId);
                if (foundNode != null) {
                    return foundNode;
                }
            }
        }
        return null;
    }

    // Helper method to find a node by its hash code
    private AccessibilityNodeInfo findNodeByHashCode(int hashCode) {
        List<AccessibilityWindowInfo> windows = getWindows();

        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo rootNode = window.getRoot();
            if (rootNode != null) {
                AccessibilityNodeInfo foundNode = findNodeByHashCodeRecursive(rootNode, hashCode);
                if (foundNode != null) {
                    return foundNode;
                }
            }
        }
        return null;
    }

    // Method to find nodes by view ID and return their information
    public void findByViewId(String viewId) {
        findByViewId(viewId, false);
    }
    
    public void findByViewId(String viewId, boolean verbose) {
        try {
            List<AccessibilityNodeInfo> foundNodes = new ArrayList<>();
            List<AccessibilityWindowInfo> windows = getWindows();

            Log.d(LOG_TAG, "findByViewId: searching for '" + viewId + "' in " + windows.size() + " windows");

            // Use the native findAccessibilityNodeInfosByViewId method
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo window = windows.get(i);
                if (window != null) {
                    Log.d(LOG_TAG, "Window " + i + ": type=" + window.getType() + 
                          ", isActive=" + window.isActive() + 
                          ", hasRoot=" + (window.getRoot() != null));
                    
                    AccessibilityNodeInfo rootNode = window.getRoot();
                    if (rootNode != null) {
                        List<AccessibilityNodeInfo> nodesInWindow = rootNode.findAccessibilityNodeInfosByViewId(viewId);
                        if (nodesInWindow != null && !nodesInWindow.isEmpty()) {
                            Log.d(LOG_TAG, "Found " + nodesInWindow.size() + " nodes in window " + i + " (type=" + window.getType() + ")");
                            foundNodes.addAll(nodesInWindow);
                        }
                    }
                }
            }

            // Build response with found nodes
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", true);
            resultJson.put("viewId", viewId);
            resultJson.put("count", foundNodes.size());

            JSONArray nodesArray = new JSONArray();
            for (AccessibilityNodeInfo node : foundNodes) {
                JSONObject nodeInfo = createNodeInfoJson(node, verbose);
                nodesArray.put(nodeInfo);
            }
            resultJson.put("nodes", nodesArray);

            // Send result
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            
            Log.d(LOG_TAG, "Found " + foundNodes.size() + " nodes with viewId: " + viewId);

        } catch (Exception e) {
            String errorMessage = "Error finding nodes by viewId: " + e.getMessage();
            Log.e(LOG_TAG, errorMessage, e);
            sendFindByViewIdResult(false, errorMessage);
        }
    }

    // Method to find nodes by text and return their information
    public void findByText(String text) {
        findByText(text, false);
    }
    
    public void findByText(String text, boolean verbose) {
        try {
            List<AccessibilityNodeInfo> foundNodes = new ArrayList<>();
            List<AccessibilityWindowInfo> windows = getWindows();

            Log.d(LOG_TAG, "findByText: Starting search for '" + text + "' across " + windows.size() + " windows");

            // Use the native findAccessibilityNodeInfosByText method
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo window = windows.get(i);
                AccessibilityNodeInfo rootNode = window.getRoot();
                
                Log.d(LOG_TAG, "findByText: Window " + i + " - Title: " + window.getTitle() + 
                      ", Type: " + window.getType() + ", Active: " + window.isActive() + 
                      ", Root null: " + (rootNode == null));
                
                if (rootNode != null) {
                    // Debug: check root node properties
                    Log.d(LOG_TAG, "findByText: Window " + i + " root - class: " + rootNode.getClassName() + 
                          ", childCount: " + rootNode.getChildCount() + 
                          ", text: '" + rootNode.getText() + "'");
                    
                    // Test if the native method works at all
                    List<AccessibilityNodeInfo> nodesInWindow = rootNode.findAccessibilityNodeInfosByText(text);
                    int windowNodeCount = (nodesInWindow != null) ? nodesInWindow.size() : 0;
                    
                    Log.d(LOG_TAG, "findByText: Window " + i + " found " + windowNodeCount + " nodes");
                    
                    // Debug: also try searching for a very common character that should exist
                    if (i == 3 && windowNodeCount == 0) { // Only for main Slack window
                        Log.d(LOG_TAG, "findByText: DEBUG - Testing if native method works at all on Slack window");
                        List<AccessibilityNodeInfo> testNodes = rootNode.findAccessibilityNodeInfosByText("a");
                        Log.d(LOG_TAG, "findByText: DEBUG - Native method found " + 
                              ((testNodes != null) ? testNodes.size() : 0) + " nodes for 'a'");
                        
                        // Also test our custom method on the same root
                        List<AccessibilityNodeInfo> customTestNodes = CustomNodeFinder.findNodesByText(rootNode, "Activity");
                        Log.d(LOG_TAG, "findByText: DEBUG - Custom method found " + customTestNodes.size() + " nodes for 'Activity'");
                    }
                    
                    if (nodesInWindow != null && !nodesInWindow.isEmpty()) {
                        foundNodes.addAll(nodesInWindow);
                        
                        // Log first few nodes found in this window
                        for (int j = 0; j < Math.min(3, nodesInWindow.size()); j++) {
                            AccessibilityNodeInfo node = nodesInWindow.get(j);
                            Log.d(LOG_TAG, "findByText: Window " + i + " node " + j + " - " + 
                                  node.getClassName() + " text:'" + node.getText() + 
                                  "' desc:'" + node.getContentDescription() + "'");
                        }
                    }
                }
            }

            // Build response with found nodes
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", true);
            resultJson.put("text", text);
            resultJson.put("count", foundNodes.size());

            JSONArray nodesArray = new JSONArray();
            for (AccessibilityNodeInfo node : foundNodes) {
                JSONObject nodeInfo = createNodeInfoJson(node, verbose);
                nodesArray.put(nodeInfo);
            }
            resultJson.put("nodes", nodesArray);

            // Send result
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            
            Log.d(LOG_TAG, "Found " + foundNodes.size() + " nodes with text: " + text);

        } catch (Exception e) {
            String errorMessage = "Error finding nodes by text: " + e.getMessage();
            Log.e(LOG_TAG, errorMessage, e);
            sendFindByTextResult(false, errorMessage);
        }
    }

    // Helper method to create node info JSON
    private JSONObject createNodeInfoJson(AccessibilityNodeInfo node) throws JSONException {
        return createNodeInfoJson(node, false);
    }
    
    // Helper method to create node info JSON with verbose option
    private JSONObject createNodeInfoJson(AccessibilityNodeInfo node, boolean verbose) throws JSONException {
        JSONObject nodeInfo = new JSONObject();
        
        // Basic properties
        nodeInfo.put("hashCode", node.hashCode());
        nodeInfo.put("className", node.getClassName() != null ? node.getClassName().toString() : "");
        nodeInfo.put("text", node.getText() != null ? node.getText().toString() : "");
        nodeInfo.put("contentDescription", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        nodeInfo.put("viewIdResourceName", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        
        // Parent hashCode
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            nodeInfo.put("parentHashCode", parent.hashCode());
            parent.recycle(); // Important: recycle the parent node after use
        } else {
            nodeInfo.put("parentHashCode", null);
        }
        
        // State properties
        nodeInfo.put("isClickable", node.isClickable());
        nodeInfo.put("isEnabled", node.isEnabled());
        nodeInfo.put("isFocusable", node.isFocusable());
        nodeInfo.put("isFocused", node.isFocused());
        nodeInfo.put("isScrollable", node.isScrollable());
        nodeInfo.put("isCheckable", node.isCheckable());
        nodeInfo.put("isChecked", node.isChecked());
        nodeInfo.put("isSelected", node.isSelected());
        
        // Bounds
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        JSONObject boundsJson = new JSONObject();
        boundsJson.put("left", bounds.left);
        boundsJson.put("top", bounds.top);
        boundsJson.put("right", bounds.right);
        boundsJson.put("bottom", bounds.bottom);
        nodeInfo.put("boundsInScreen", boundsJson);
        
        // Verbose properties - only included when verbose flag is true
        if (verbose) {
            // Additional text properties
            if (node.getHintText() != null) {
                nodeInfo.put("hintText", node.getHintText().toString());
            }
            if (node.getError() != null) {
                nodeInfo.put("errorText", node.getError().toString());
            }
            // These methods are available since API 28 (our minSdk)
            if (node.getTooltipText() != null) {
                nodeInfo.put("tooltipText", node.getTooltipText().toString());
            }
            if (node.getPaneTitle() != null) {
                nodeInfo.put("paneTitle", node.getPaneTitle().toString());
            }
            // Note: getStateDescription (API 30) and getRoleDescription (API 29) 
            // are not available when compiling with certain build configurations
            // even though compileSdk is 31. These would need reflection to access.
            
            // Reference properties
            AccessibilityNodeInfo labeledBy = node.getLabeledBy();
            if (labeledBy != null) {
                nodeInfo.put("labeledByHashCode", labeledBy.hashCode());
                labeledBy.recycle();
            }
            
            // Additional state properties
            nodeInfo.put("isLongClickable", node.isLongClickable());
            nodeInfo.put("isVisibleToUser", node.isVisibleToUser());
            nodeInfo.put("isImportantForAccessibility", node.isImportantForAccessibility());
            nodeInfo.put("isContentInvalid", node.isContentInvalid());
            nodeInfo.put("isScreenReaderFocusable", node.isScreenReaderFocusable());
            
            // Collection properties
            AccessibilityNodeInfo.CollectionInfo collectionInfo = node.getCollectionInfo();
            if (collectionInfo != null) {
                JSONObject collectionJson = new JSONObject();
                collectionJson.put("rowCount", collectionInfo.getRowCount());
                collectionJson.put("columnCount", collectionInfo.getColumnCount());
                nodeInfo.put("collectionInfo", collectionJson);
            }
            
            AccessibilityNodeInfo.CollectionItemInfo collectionItemInfo = node.getCollectionItemInfo();
            if (collectionItemInfo != null) {
                JSONObject itemJson = new JSONObject();
                itemJson.put("rowIndex", collectionItemInfo.getRowIndex());
                itemJson.put("columnIndex", collectionItemInfo.getColumnIndex());
                itemJson.put("rowSpan", collectionItemInfo.getRowSpan());
                itemJson.put("columnSpan", collectionItemInfo.getColumnSpan());
                nodeInfo.put("collectionItemInfo", itemJson);
            }
            
            // Action list
            JSONArray actions = new JSONArray();
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                JSONObject actionJson = new JSONObject();
                actionJson.put("id", action.getId());
                if (action.getLabel() != null) {
                    actionJson.put("label", action.getLabel().toString());
                }
                actions.put(actionJson);
            }
            if (actions.length() > 0) {
                nodeInfo.put("actionList", actions);
            }
            
            // Other properties
            nodeInfo.put("windowId", node.getWindowId());
            nodeInfo.put("childCount", node.getChildCount());
        }
        
        return nodeInfo;
    }


    // Send error result for find commands
    private void sendFindByViewIdResult(boolean success, String message) {
        try {
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", success);
            resultJson.put("message", message);

            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending findByViewId result: " + e.getMessage());
        }
    }

    // Send error result for find commands
    private void sendFindByTextResult(boolean success, String message) {
        try {
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", success);
            resultJson.put("message", message);

            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending findByText result: " + e.getMessage());
        }
    }

    // Recursive helper method to search through the node tree by resource ID
    private AccessibilityNodeInfo findNodeByResourceIdRecursive(AccessibilityNodeInfo node, String resourceId) {
        if (node == null) return null;

        // Check if current node matches the resource ID
        if (resourceId.equals(node.getViewIdResourceName())) {
            return node;
        }

        // Search through children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo foundNode = findNodeByResourceIdRecursive(child, resourceId);
                if (foundNode != null) {
                    return foundNode;
                }
            }
        }

        return null;
    }

    // Recursive helper method to search through the node tree by hash code
    private AccessibilityNodeInfo findNodeByHashCodeRecursive(AccessibilityNodeInfo node, int hashCode) {
        if (node == null) return null;

        // Check if current node matches the hash code
        if (node.hashCode() == hashCode) {
            return node;
        }

        // Search through children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo foundNode = findNodeByHashCodeRecursive(child, hashCode);
                if (foundNode != null) {
                    return foundNode;
                }
            }
        }

        return null;
    }

    // Send action result back to the client
    public void sendActionResult(boolean success, String message) {
        try {
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "actionResult");
            resultJson.put("success", success);
            resultJson.put("message", message);

            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            Log.d(LOG_TAG, "Action result sent: " + message);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending action result: " + e.getMessage());
        }
    }

    @Override
    public void takeScreenshot(int displayId, @NonNull Executor executor, @NonNull TakeScreenshotCallback callback) {
        super.takeScreenshot(displayId, executor, callback);
    }
    public void sendJSON(JSONObject object) {
        jsonObject = object;
    }

    public void hideNotImportant() {
        int flags = this.getServiceInfo().flags;
        AccessibilityServiceInfo info = this.getServiceInfo();

        if (flags == allFlags) {
            info.flags = AccessibilityServiceInfo.DEFAULT
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FEEDBACK_GENERIC
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT;
            this.setServiceInfo(info);
        }
    }

    public void showNotImportant() {
        int flags = this.getServiceInfo().flags;
        if (flags != allFlags) {
            AccessibilityServiceInfo info = this.getServiceInfo();
            info.flags = allFlags;
            this.setServiceInfo(info);
        }

    }

    public void sendTree() {
        try {
            JSONObject treeResponse = new JSONObject();
            treeResponse.put("type", "tree");
            
            JSONArray originalChildren = jsonObject.getJSONArray("children");
            JSONArray children;
            
            // Apply filtering only if visibleOnly parameter was set to true
            if (manualCaptureVisibleOnly) {
                Log.d(LOG_TAG, "Applying visibleOnly filtering to manual tree capture");
                children = removeInvisibleLeafNodes(originalChildren);
            } else {
                Log.d(LOG_TAG, "Sending full tree (no visibleOnly filtering)");
                children = originalChildren;
            }
            
            treeResponse.put("children", children);
            
            Intent announcementIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            announcementIntent.putExtra("messageData", treeResponse.toString());
            startService(announcementIntent);
            Log.d(LOG_TAG, "message sent");
        } catch (Exception e) {
            Log.e(LOG_TAG,e.getMessage());
        }
    }
    public void sendAnnouncement(String announcement) {
        try {
            JSONObject announcementJson = new JSONObject();
            announcementJson.put("announcement", announcement);
            Intent announcementIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            announcementIntent.putExtra("messageData", announcementJson.toString());
            startService(announcementIntent);
            Log.d(LOG_TAG, "announcement sent");
        } catch (Exception e) {
            Log.e(LOG_TAG,e.getMessage());
        }
    }

    // Custom recursive find by text (for comparison with native method)
    public void customFindByText(String text) {
        customFindByText(text, false);
    }
    
    public void customFindByText(String text, boolean verbose) {
        try {
            List<AccessibilityNodeInfo> foundNodes = new ArrayList<>();
            List<AccessibilityWindowInfo> windows = getWindows();

            // Use custom recursive search
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo rootNode = window.getRoot();
                if (rootNode != null) {
                    List<AccessibilityNodeInfo> nodesInWindow = CustomNodeFinder.findNodesByText(rootNode, text);
                    if (nodesInWindow != null && !nodesInWindow.isEmpty()) {
                        foundNodes.addAll(nodesInWindow);
                    }
                }
            }
            
            // Get tree stats for debugging
            StringBuilder statsBuilder = new StringBuilder();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo rootNode = window.getRoot();
                if (rootNode != null) {
                    statsBuilder.append("Window: ").append(window.getTitle()).append(" - ");
                    statsBuilder.append(CustomNodeFinder.getTreeStats(rootNode)).append("; ");
                }
            }
            
            // Build response with found nodes
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", true);
            resultJson.put("method", "customFindByText");
            resultJson.put("text", text);
            resultJson.put("count", foundNodes.size());
            resultJson.put("stats", statsBuilder.toString());
            
            JSONArray nodesArray = new JSONArray();
            for (AccessibilityNodeInfo node : foundNodes) {
                nodesArray.put(createNodeInfoJson(node, verbose));
            }
            resultJson.put("nodes", nodesArray);
            
            // Send result
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in customFindByText: " + e.getMessage());
            sendCustomFindResult(false, "customFindByText", e.getMessage());
        }
    }

    // Custom recursive find by viewId (for comparison with native method)
    public void customFindByViewId(String viewId) {
        customFindByViewId(viewId, false);
    }
    
    public void customFindByViewId(String viewId, boolean verbose) {
        try {
            List<AccessibilityNodeInfo> foundNodes = new ArrayList<>();
            List<AccessibilityWindowInfo> windows = getWindows();

            // Use custom recursive search
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo rootNode = window.getRoot();
                if (rootNode != null) {
                    List<AccessibilityNodeInfo> nodesInWindow = CustomNodeFinder.findNodesByViewId(rootNode, viewId);
                    if (nodesInWindow != null && !nodesInWindow.isEmpty()) {
                        foundNodes.addAll(nodesInWindow);
                    }
                }
            }
            
            // Build response with found nodes
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", true);
            resultJson.put("method", "customFindByViewId");
            resultJson.put("viewId", viewId);
            resultJson.put("count", foundNodes.size());
            
            JSONArray nodesArray = new JSONArray();
            for (AccessibilityNodeInfo node : foundNodes) {
                nodesArray.put(createNodeInfoJson(node, verbose));
            }
            resultJson.put("nodes", nodesArray);
            
            // Send result
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in customFindByViewId: " + e.getMessage());
            sendCustomFindResult(false, "customFindByViewId", e.getMessage());
        }
    }

    // Custom regex find by text pattern
    public void findByRegex(String regexPattern) {
        findByRegex(regexPattern, false);
    }
    
    public void findByRegex(String regexPattern, boolean verbose) {
        try {
            List<AccessibilityNodeInfo> foundNodes = new ArrayList<>();
            List<AccessibilityWindowInfo> windows = getWindows();

            // Use custom regex search
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo rootNode = window.getRoot();
                if (rootNode != null) {
                    List<AccessibilityNodeInfo> nodesInWindow = CustomNodeFinder.findNodesByRegex(rootNode, regexPattern);
                    if (nodesInWindow != null && !nodesInWindow.isEmpty()) {
                        foundNodes.addAll(nodesInWindow);
                    }
                }
            }
            
            // Build response with found nodes
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", true);
            resultJson.put("method", "findByRegex");
            resultJson.put("pattern", regexPattern);
            resultJson.put("count", foundNodes.size());
            
            JSONArray nodesArray = new JSONArray();
            for (AccessibilityNodeInfo node : foundNodes) {
                nodesArray.put(createNodeInfoJson(node, verbose));
            }
            resultJson.put("nodes", nodesArray);
            
            // Send result
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in findByRegex: " + e.getMessage());
            sendCustomFindResult(false, "findByRegex", e.getMessage());
        }
    }

    public void findByProps(JSONObject properties) {
        findByProps(properties, false);
    }
    
    public void findByProps(JSONObject properties, boolean verbose) {
        try {
            List<AccessibilityNodeInfo> foundNodes = new ArrayList<>();
            List<AccessibilityWindowInfo> windows = getWindows();

            // Use custom properties search
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo rootNode = window.getRoot();
                if (rootNode != null) {
                    List<AccessibilityNodeInfo> nodesInWindow = CustomNodeFinder.findNodesByProps(rootNode, properties);
                    if (nodesInWindow != null && !nodesInWindow.isEmpty()) {
                        foundNodes.addAll(nodesInWindow);
                    }
                }
            }
            
            // Build response with found nodes
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", true);
            resultJson.put("method", "findByProps");
            resultJson.put("properties", properties);
            resultJson.put("count", foundNodes.size());
            
            JSONArray nodesArray = new JSONArray();
            for (AccessibilityNodeInfo node : foundNodes) {
                nodesArray.put(createNodeInfoJson(node, verbose));
            }
            resultJson.put("nodes", nodesArray);
            
            // Send result
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in findByProps: " + e.getMessage());
            sendCustomFindResult(false, "findByProps", e.getMessage());
        }
    }

    // Send error result for custom find commands
    private void sendCustomFindResult(boolean success, String method, String message) {
        try {
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "findResult");
            resultJson.put("success", success);
            resultJson.put("method", method);
            resultJson.put("message", message);

            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            resultIntent.putExtra("messageData", resultJson.toString());
            startService(resultIntent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending custom find result: " + e.getMessage());
        }
    }

    // Handle UI content changes for stability detection
    private void handleUIContentChange(AccessibilityEvent event) {
        // UI is changing - reset stability timer
        if (captureStableTree != null) {
            stabilityHandler.removeCallbacks(captureStableTree);
        }
        
        // Schedule tree capture after UI has been stable for 1 second
        captureStableTree = () -> {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                
                if (windows != null && !windows.isEmpty()) {
                    // Use fast tree capture for stable trees
                    TreeDebug.logNodeTreesFast(windows, this);
                    
                    // TreeDebug calls sendJSON() which stores tree in jsonObject
                    if (jsonObject != null) {
                        // Compare with previous tree to detect actual changes
                        if (stableUITree == null || hasTreeChanged(stableUITree, jsonObject)) {
                            JSONObject oldTree = stableUITree; // Keep reference for diff logging
                            stableUITree = new JSONObject(jsonObject.toString()); // Deep copy
                            stableTreeTimestamp = System.currentTimeMillis();
                            
                            sendStableTree();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error capturing stable UI tree: " + e.getMessage());
            }
        };
        stabilityHandler.postDelayed(captureStableTree, UI_STABILITY_DELAY);
    }

    // Handle scroll events with aggregation
    private void handleScrollEvent(AccessibilityEvent event) {
        Log.d(LOG_TAG, "Scroll event detected - X: " + event.getScrollX() + ", Y: " + event.getScrollY() + 
               ", MaxX: " + event.getMaxScrollX() + ", MaxY: " + event.getMaxScrollY() + 
               ", Package: " + event.getPackageName());
        // Accumulate scroll data
        if (scrollEventCount == 0) {
            // First scroll event in this sequence
            firstScrollEvent = AccessibilityEvent.obtain(event);
            totalScrollX = event.getScrollX();
            totalScrollY = event.getScrollY();
            scrollTimestamps.clear(); // Reset timestamp list
        } else {
            // Accumulate scroll deltas
            totalScrollX += event.getScrollX();
            totalScrollY += event.getScrollY();
        }
        
        // Add this event's timestamp to the list
        scrollTimestamps.add(System.currentTimeMillis());
        scrollEventCount++;
        
        // Cancel previous scroll-end detection
        if (scrollEndRunnable != null) {
            scrollHandler.removeCallbacks(scrollEndRunnable);
        }
        
        // Schedule new scroll-end detection
        scrollEndRunnable = () -> {
            sendScrollEndEvent();
            resetScrollAccumulation();
        };
        scrollHandler.postDelayed(scrollEndRunnable, SCROLL_END_DELAY);
    }

    // Handle text input events with session tracking
    private void handleTextChangedEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        
        int sourceHashCode = source.hashCode();
        
        // Check if text change is from different field than expected
        if (currentTextFieldHashCode != -1 && sourceHashCode != currentTextFieldHashCode) {
            // Text input from different field - end old session, start new
            if (isTextSessionActive()) {
                endTextSession();
            }
            startNewTextSession(source);
        }
        
        // Initialize session if first text event
        if (textEventCount == 0) {
            if (activeTextField == null) {
                startNewTextSession(source);
            }
            // Capture tree context on first text input (lazy capture)
            captureTextFieldContext();
            firstTextTime = event.getEventTime();
        }
        
        // Accumulate text changes
        int addedCount = event.getAddedCount();
        boolean isProbablyPaste = (addedCount > 10); // threshold for paste detection
        
        if (event.getText() != null && !event.getText().isEmpty()) {
            for (CharSequence text : event.getText()) {
                if (text != null) {
                    sessionText.setLength(0); // Replace with current text
                    sessionText.append(text.toString());
                    break; // Take first non-null text
                }
            }
        }
        
        textEventCount++;
        if (isProbablyPaste) {
            pasteEventCount++;
        }
        
        // Reset timeout
        if (textEndRunnable != null) {
            textHandler.removeCallbacks(textEndRunnable);
        }
        textEndRunnable = this::endTextSession;
        textHandler.postDelayed(textEndRunnable, TEXT_INPUT_TIMEOUT);
    }

    // Handle focus events (only for text fields)
    private void handleFocusEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo focused = event.getSource();
        
        // Only care about focus for text input fields
        if (isTextInputField(focused)) {
            handleTextFieldFocus(focused);
        }
        
        // Send simple focus event without tree association
        sendAccessibilityEvent(event);
    }

    // Handle click events
    private void handleClickEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo clicked = event.getSource();
        
        if (isTextInputField(clicked)) {
            handleTextFieldFocus(clicked);
        } else {
            // Clicked non-text field - end any active text session
            if (isTextSessionActive()) {
                endTextSession();
            }
        }
        
        // Send simple click event without tree association
        sendAccessibilityEvent(event);
    }

    // Handle selection events
    private void handleSelectionEvent(AccessibilityEvent event) {
        // Send simple selection event without tree association
        sendAccessibilityEvent(event);
    }

    // Handle window state changes (no tree capture - too frequent)
    private void handleWindowStateEvent(AccessibilityEvent event) {
        try {
            JSONObject eventJson = createBaseEventJson(event);

            Intent eventIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            eventIntent.putExtra("messageData", eventJson.toString());
            startService(eventIntent);
            Log.d(LOG_TAG, "Window state change event sent");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending window state event: " + e.getMessage(), e);
        }
    }


    // Send simple accessibility event without tree capture
    private void sendAccessibilityEvent(AccessibilityEvent event) {
        try {
            JSONObject eventJson = createBaseEventJson(event);
            
            Intent eventIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            eventIntent.putExtra("messageData", eventJson.toString());
            startService(eventIntent);
            Log.d(LOG_TAG, "Accessibility event sent: " + getEventTypeName(event.getEventType()));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending accessibility event: " + e.getMessage(), e);
        }
    }

    // Send stable tree to all clients
    private void sendStableTree() {
        try {
            JSONObject treeResponse = new JSONObject();
            treeResponse.put("type", "stableTree");
            treeResponse.put("timestamp", stableTreeTimestamp);
            
            // Filter out invisible leaf nodes to reduce size while preserving structure
            JSONArray originalChildren = stableUITree.getJSONArray("children");
            JSONArray filteredChildren = removeInvisibleLeafNodes(originalChildren);
            treeResponse.put("children", filteredChildren);
            
            Intent treeIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            treeIntent.putExtra("messageData", treeResponse.toString());
            startService(treeIntent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending stable tree: " + e.getMessage(), e);
        }
    }
    
    // Remove invisible leaf nodes recursively while preserving tree structure
    private JSONArray removeInvisibleLeafNodes(JSONArray children) throws Exception {
        JSONArray filtered = new JSONArray();
        
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.getJSONObject(i);
            
            // First, recursively process this node's children
            if (child.has("children")) {
                JSONArray childChildren = child.getJSONArray("children");
                JSONArray filteredChildChildren = removeInvisibleLeafNodes(childChildren);
                child.put("children", filteredChildChildren);
            }
            
            // Check if this node is invisible
            boolean isVisible = true;
            if (child.has("metadata")) {
                JSONObject metadata = child.getJSONObject("metadata");
                if (metadata.has("visibility") && "invisible".equals(metadata.getString("visibility"))) {
                    isVisible = false;
                }
            }
            
            // Only remove invisible nodes that are now leaf nodes (no children after filtering)
            boolean hasChildren = child.has("children") && child.getJSONArray("children").length() > 0;
            
            if (isVisible || hasChildren) {
                // Keep visible nodes and invisible nodes that have children (structural containers)
                filtered.put(child);
            }
            // Skip invisible leaf nodes (they serve no structural purpose)
        }
        
        return filtered;
    }

    // Create base event JSON without tree
    private JSONObject createBaseEventJson(AccessibilityEvent event) throws Exception {
        JSONObject eventJson = new JSONObject();
        eventJson.put("type", "accessibilityEvent");
        eventJson.put("eventType", getEventTypeName(event.getEventType()));
        eventJson.put("eventTypeId", event.getEventType());
        eventJson.put("packageName", event.getPackageName() != null ? event.getPackageName().toString() : "");
        eventJson.put("className", event.getClassName() != null ? event.getClassName().toString() : "");
        eventJson.put("timestamp", System.currentTimeMillis());
        
        // Add text content if available
        if (event.getText() != null && !event.getText().isEmpty()) {
            JSONArray textArray = new JSONArray();
            for (CharSequence text : event.getText()) {
                if (text != null && text.length() > 0) {
                    textArray.put(text.toString());
                }
            }
            if (textArray.length() > 0) {
                eventJson.put("text", textArray);
            }
        }

        // Add content description if available
        if (event.getContentDescription() != null && event.getContentDescription().length() > 0) {
            eventJson.put("contentDescription", event.getContentDescription().toString());
        }

        // Add source node information if available (matching TreeDebug format)
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            JSONObject sourceInfo = new JSONObject();
            if (source.getViewIdResourceName() != null) {
                sourceInfo.put("resourceId", source.getViewIdResourceName());
            }
            if (source.getText() != null) {
                sourceInfo.put("text", source.getText().toString());
            }
            if (source.getContentDescription() != null) {
                sourceInfo.put("contentDescription", source.getContentDescription().toString());
            }
            
            // Use TreeDebug format: simplified class name in "name" field
            if (source.getClassName() != null) {
                sourceInfo.put("name", getSimpleName(source.getClassName()));
            }
            
            // Metadata object matching TreeDebug structure
            JSONObject metadata = new JSONObject();
            metadata.put("hashCode", source.hashCode());
            if (source.getClassName() != null) {
                metadata.put("role", getSimpleName(source.getClassName()));
            }
            sourceInfo.put("metadata", metadata);
            
            eventJson.put("source", sourceInfo);
        }

        // Add additional properties based on event type
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                eventJson.put("beforeText", event.getBeforeText() != null ? event.getBeforeText().toString() : "");
                eventJson.put("fromIndex", event.getFromIndex());
                eventJson.put("addedCount", event.getAddedCount());
                eventJson.put("removedCount", event.getRemovedCount());
                break;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                eventJson.put("scrollX", event.getScrollX());
                eventJson.put("scrollY", event.getScrollY());
                eventJson.put("maxScrollX", event.getMaxScrollX());
                eventJson.put("maxScrollY", event.getMaxScrollY());
                break;
        }
        
        return eventJson;
    }

    // Send scroll sequence end event with accumulated data
    private void sendScrollEndEvent() {
        if (firstScrollEvent == null) return;
        
        try {
            JSONObject eventJson = new JSONObject();
            eventJson.put("type", "accessibilityEvent");
            eventJson.put("eventType", "SCROLL_SEQUENCE_END");
            eventJson.put("packageName", firstScrollEvent.getPackageName().toString());
            eventJson.put("className", firstScrollEvent.getClassName().toString());
            // Scroll event timestamps
            JSONArray timestampArray = new JSONArray();
            for (Long timestamp : scrollTimestamps) {
                timestampArray.put(timestamp);
            }
            eventJson.put("scrollTimestamps", timestampArray);
            
            // Accumulated scroll data
            eventJson.put("totalScrollX", totalScrollX);
            eventJson.put("totalScrollY", totalScrollY);
            
            // Source information (matching TreeDebug format)
            AccessibilityNodeInfo source = firstScrollEvent.getSource();
            if (source != null) {
                JSONObject sourceInfo = new JSONObject();
                if (source.getViewIdResourceName() != null) {
                    sourceInfo.put("resourceId", source.getViewIdResourceName());
                }
                if (source.getText() != null) {
                    sourceInfo.put("text", source.getText().toString());
                }
                if (source.getContentDescription() != null) {
                    sourceInfo.put("contentDescription", source.getContentDescription().toString());
                }
                
                // Use TreeDebug format
                if (source.getClassName() != null) {
                    sourceInfo.put("name", getSimpleName(source.getClassName()));
                }
                
                JSONObject metadata = new JSONObject();
                metadata.put("hashCode", source.hashCode());
                if (source.getClassName() != null) {
                    metadata.put("role", getSimpleName(source.getClassName()));
                }
                sourceInfo.put("metadata", metadata);
                eventJson.put("source", sourceInfo);
                
                // No longer using before-tree logic
            }

            Intent eventIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            eventIntent.putExtra("messageData", eventJson.toString());
            startService(eventIntent);
            Log.d(LOG_TAG, "Scroll sequence ended - X: " + totalScrollX + ", Y: " + totalScrollY + ", Events: " + scrollEventCount);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending scroll end event: " + e.getMessage());
        }
    }

    // Text field session management
    private boolean isTextInputField(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String className = node.getClassName().toString();
        return className.contains("EditText") || 
               className.contains("TextInputEditText") ||
               node.isEditable();
    }

    private void handleTextFieldFocus(AccessibilityNodeInfo textField) {
        // End previous session if switching fields
        if (isTextSessionActive() && !isSameField(textField)) {
            endTextSession();
        }
        
        // Prepare for new text session
        activeTextField = textField;
        currentTextFieldHashCode = textField.hashCode();
    }

    private boolean isTextSessionActive() {
        return textEventCount > 0 || activeTextField != null;
    }

    private boolean isSameField(AccessibilityNodeInfo field) {
        return field != null && field.hashCode() == currentTextFieldHashCode;
    }

    private void startNewTextSession(AccessibilityNodeInfo newTextField) {
        activeTextField = newTextField;
        currentTextFieldHashCode = newTextField.hashCode();
        resetTextAccumulation();
    }

    private void captureTextFieldContext() {
        if (activeTextField == null) return;
        
        try {
            AccessibilityWindowInfo window = activeTextField.getWindow();
            if (window != null) {
                List<AccessibilityWindowInfo> windows = List.of(window);
                TreeDebug.logNodeTrees(windows, this);
                // Store tree capture flag
                textFieldTree = new JSONObject();
                textFieldTree.put("captured", true);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error capturing text field context", e);
        }
    }

    private void endTextSession() {
        if (textEventCount == 0) {
            resetTextAccumulation();
            return;
        }
        
        try {
            JSONObject eventJson = new JSONObject();
            eventJson.put("type", "accessibilityEvent");
            eventJson.put("eventType", "TEXT_SEQUENCE_END");
            eventJson.put("startTimestamp", firstTextTime);
            eventJson.put("endTimestamp", System.currentTimeMillis());
            eventJson.put("finalText", sessionText.toString());
            eventJson.put("textEventCount", textEventCount);
            eventJson.put("pasteEventCount", pasteEventCount);
            eventJson.put("sessionDuration", System.currentTimeMillis() - firstTextTime);
            eventJson.put("containsPaste", pasteEventCount > 0);
            
            // Text field information (matching TreeDebug format)
            if (activeTextField != null) {
                JSONObject fieldInfo = new JSONObject();
                if (activeTextField.getViewIdResourceName() != null) {
                    fieldInfo.put("resourceId", activeTextField.getViewIdResourceName());
                }
                if (activeTextField.getText() != null) {
                    fieldInfo.put("text", activeTextField.getText().toString());
                }
                if (activeTextField.getContentDescription() != null) {
                    fieldInfo.put("contentDescription", activeTextField.getContentDescription().toString());
                }
                
                // Use TreeDebug format
                if (activeTextField.getClassName() != null) {
                    fieldInfo.put("name", getSimpleName(activeTextField.getClassName()));
                }
                
                JSONObject metadata = new JSONObject();
                metadata.put("hashCode", activeTextField.hashCode());
                if (activeTextField.getClassName() != null) {
                    metadata.put("role", getSimpleName(activeTextField.getClassName()));
                }
                fieldInfo.put("metadata", metadata);
                eventJson.put("textField", fieldInfo);
            }
            
            // Tree was captured at session start
            if (textFieldTree != null) {
                eventJson.put("hasTree", true);
            }

            Intent eventIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            eventIntent.putExtra("messageData", eventJson.toString());
            startService(eventIntent);
            Log.d(LOG_TAG, "Text session ended - Text: '" + sessionText.toString() + "', Events: " + textEventCount);
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending text session end event: " + e.getMessage());
        }
        
        resetTextAccumulation();
    }

    private void resetScrollAccumulation() {
        totalScrollX = 0;
        totalScrollY = 0;
        scrollEventCount = 0;
        scrollTimestamps.clear();
        if (firstScrollEvent != null) {
            firstScrollEvent.recycle();
            firstScrollEvent = null;
        }
    }

    private void resetTextAccumulation() {
        sessionText.setLength(0);
        textEventCount = 0;
        firstTextTime = 0;
        activeTextField = null;
        currentTextFieldHashCode = -1;
        textFieldTree = null;
        pasteEventCount = 0;
    }

    // Cancel any pending stability capture (called when client requests tree)
    public void cancelPendingStabilityCapture() {
        if (captureStableTree != null) {
            stabilityHandler.removeCallbacks(captureStableTree);
            captureStableTree = null;
            Log.d(LOG_TAG, "Cancelled pending stability capture for client request");
        }
    }

    // Helper method to get simple class name (matching TreeDebug format)
    private String getSimpleName(CharSequence fullName) {
        if (fullName == null) return "";
        int dotIndex = fullName.toString().lastIndexOf('.');
        if (dotIndex < 0) {
            dotIndex = 0;
        }
        return fullName.subSequence(dotIndex, fullName.length()).toString().replace(".", "");
    }

    // Helper method to convert event type to readable name
    private String getEventTypeName(int eventType) {
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED: return "VIEW_CLICKED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "VIEW_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "VIEW_TEXT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "WINDOW_STATE_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: return "VIEW_SCROLLED";
            case AccessibilityEvent.TYPE_VIEW_SELECTED: return "VIEW_SELECTED";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: return "VIEW_ACCESSIBILITY_FOCUSED";
            case AccessibilityEvent.TYPE_ANNOUNCEMENT: return "ANNOUNCEMENT";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "WINDOW_CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED: return "VIEW_CONTEXT_CLICKED";
            default: return "UNKNOWN_" + eventType;
        }
    }

    // Check if tree content has meaningfully changed (ignoring volatile node IDs)
    private boolean hasTreeChanged(JSONObject oldTree, JSONObject newTree) {
        try {
            // Remove volatile IDs before comparison
            JSONObject oldClean = removeNodeIds(oldTree);
            JSONObject newClean = removeNodeIds(newTree);
            
            String oldStr = oldClean.toString();
            String newStr = newClean.toString();
            return !oldStr.equals(newStr);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error comparing trees: " + e.getMessage());
            return true; // Assume changed if we can't compare
        }
    }
    
    // Remove volatile node IDs from tree for comparison
    private JSONObject removeNodeIds(JSONObject tree) throws Exception {
        JSONObject cleaned = new JSONObject();
        
        // Copy all properties except "id"
        java.util.Iterator<String> keys = tree.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("id")) {
                Object value = tree.get(key);
                if (value instanceof JSONArray) {
                    cleaned.put(key, removeNodeIdsFromArray((JSONArray) value));
                } else if (value instanceof JSONObject) {
                    cleaned.put(key, removeNodeIds((JSONObject) value));
                } else {
                    cleaned.put(key, value);
                }
            }
        }
        
        return cleaned;
    }
    
    // Remove node IDs from array of objects
    private JSONArray removeNodeIdsFromArray(JSONArray array) throws Exception {
        JSONArray cleaned = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (item instanceof JSONObject) {
                cleaned.put(removeNodeIds((JSONObject) item));
            } else if (item instanceof JSONArray) {
                cleaned.put(removeNodeIdsFromArray((JSONArray) item));
            } else {
                cleaned.put(item);
            }
        }
        return cleaned;
    }

    // Log differences between trees to help debug what's changing
    private void logTreeDifferences(JSONObject oldTree, JSONObject newTree) {
        try {
            // Compare top-level properties
            JSONArray oldChildren = oldTree.optJSONArray("children");
            JSONArray newChildren = newTree.optJSONArray("children");
            
            if (oldChildren != null && newChildren != null) {
                if (oldChildren.length() != newChildren.length()) {
                    Log.d(LOG_TAG, "TREE DIFF: Window count changed from " + oldChildren.length() + " to " + newChildren.length());
                    return;
                }
                
                // Compare each window
                for (int i = 0; i < oldChildren.length(); i++) {
                    JSONObject oldWindow = oldChildren.optJSONObject(i);
                    JSONObject newWindow = newChildren.optJSONObject(i);
                    
                    if (oldWindow != null && newWindow != null) {
                        String oldTitle = oldWindow.optString("name", "");
                        String newTitle = newWindow.optString("name", "");
                        
                        if (!oldTitle.equals(newTitle)) {
                            Log.d(LOG_TAG, "TREE DIFF: Window " + i + " title changed from '" + oldTitle + "' to '" + newTitle + "'");
                        } else {
                            // Same window title, check if content changed
                            String oldWindowStr = oldWindow.toString();
                            String newWindowStr = newWindow.toString();
                            if (!oldWindowStr.equals(newWindowStr)) {
                                Log.d(LOG_TAG, "TREE DIFF: Window '" + oldTitle + "' content changed");
                                logDetailedWindowDiff(oldWindow, newWindow, oldTitle);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error logging tree differences: " + e.getMessage());
        }
    }

    // Log detailed differences within a specific window
    private void logDetailedWindowDiff(JSONObject oldWindow, JSONObject newWindow, String windowTitle) {
        try {
            String oldStr = oldWindow.toString();
            String newStr = newWindow.toString();
            
            Log.d(LOG_TAG, "TREE DIFF: Analyzing window '" + windowTitle + "' (size: " + oldStr.length() + " → " + newStr.length() + ")");
            
            // Find first character difference
            int diffIndex = findFirstDifference(oldStr, newStr);
            if (diffIndex >= 0) {
                int start = Math.max(0, diffIndex - 50);
                int oldEnd = Math.min(oldStr.length(), diffIndex + 50);
                int newEnd = Math.min(newStr.length(), diffIndex + 50);
                
                String oldSnippet = oldStr.substring(start, oldEnd);
                String newSnippet = newStr.substring(start, newEnd);
                
                Log.d(LOG_TAG, "TREE DIFF: First difference at position " + diffIndex + ":");
                Log.d(LOG_TAG, "  OLD: ..." + oldSnippet + "...");
                Log.d(LOG_TAG, "  NEW: ..." + newSnippet + "...");
                
                // Try to identify what changed by looking for patterns
                identifyChangePattern(oldSnippet, newSnippet, diffIndex);
            }
            
            // Also check specific properties more thoroughly
            String[] keysToCheck = {"text", "resourceId", "className", "bounds", "name", "metadata"};
            for (String key : keysToCheck) {
                String oldValue = getNestedStringValue(oldWindow, key);
                String newValue = getNestedStringValue(newWindow, key);
                
                if (!oldValue.equals(newValue)) {
                    Log.d(LOG_TAG, "TREE DIFF: Property '" + key + "' changed:");
                    logStringDifference(oldValue, newValue);
                    break; // Only log first property difference
                }
            }
            
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error logging detailed window diff: " + e.getMessage());
        }
    }
    
    // Find the first character position where two strings differ
    private int findFirstDifference(String str1, String str2) {
        int minLength = Math.min(str1.length(), str2.length());
        for (int i = 0; i < minLength; i++) {
            if (str1.charAt(i) != str2.charAt(i)) {
                return i;
            }
        }
        // If one string is longer, the difference is at the end of the shorter one
        return (str1.length() != str2.length()) ? minLength : -1;
    }
    
    // Try to identify what type of change occurred
    private void identifyChangePattern(String oldSnippet, String newSnippet, int diffIndex) {
        try {
            // Look for time patterns (HH:MM format)
            if (oldSnippet.matches(".*\\d{1,2}:\\d{2}.*") && newSnippet.matches(".*\\d{1,2}:\\d{2}.*")) {
                Log.d(LOG_TAG, "  PATTERN: Likely time/clock change");
            }
            // Look for percentage patterns
            else if (oldSnippet.matches(".*\\d+%.*") && newSnippet.matches(".*\\d+%.*")) {
                Log.d(LOG_TAG, "  PATTERN: Likely percentage change (battery/etc)");
            }
            // Look for numeric changes
            else if (oldSnippet.matches(".*\\d+.*") && newSnippet.matches(".*\\d+.*")) {
                Log.d(LOG_TAG, "  PATTERN: Likely numeric value change");
            }
            // Look for JSON property changes
            else if (oldSnippet.contains("\":") && newSnippet.contains("\":")) {
                Log.d(LOG_TAG, "  PATTERN: Likely JSON property value change");
            }
            else {
                Log.d(LOG_TAG, "  PATTERN: Unknown change type");
            }
        } catch (Exception e) {
            Log.d(LOG_TAG, "  PATTERN: Error analyzing - " + e.getMessage());
        }
    }
    
    // Log the specific differences between two strings
    private void logStringDifference(String oldValue, String newValue) {
        if (oldValue.length() < 200 && newValue.length() < 200) {
            // For short strings, show full values
            Log.d(LOG_TAG, "    OLD: '" + oldValue + "'");
            Log.d(LOG_TAG, "    NEW: '" + newValue + "'");
        } else {
            // For long strings, show first difference
            int diffPos = findFirstDifference(oldValue, newValue);
            if (diffPos >= 0) {
                int start = Math.max(0, diffPos - 30);
                int oldEnd = Math.min(oldValue.length(), diffPos + 30);
                int newEnd = Math.min(newValue.length(), diffPos + 30);
                
                Log.d(LOG_TAG, "    OLD: ..." + oldValue.substring(start, oldEnd) + "...");
                Log.d(LOG_TAG, "    NEW: ..." + newValue.substring(start, newEnd) + "...");
            }
        }
    }

    // Helper to recursively extract string values from nested JSON
    private String getNestedStringValue(JSONObject obj, String targetKey) {
        StringBuilder result = new StringBuilder();
        try {
            extractStringValues(obj, targetKey, result);
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return result.toString();
    }

    // Recursively extract all values for a specific key
    private void extractStringValues(JSONObject obj, String targetKey, StringBuilder result) throws Exception {
        if (obj.has(targetKey)) {
            result.append(obj.optString(targetKey, "")).append("|");
        }
        
        // Check children array
        JSONArray children = obj.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child != null) {
                    extractStringValues(child, targetKey, result);
                }
            }
        }
    }

    public void startCapture() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            
            if (windows == null || windows.isEmpty()) {
                Log.w(LOG_TAG, "No windows available for capture");
                return;
            }

            Log.d(LOG_TAG, "Total windows found: " + windows.size());

            // Filter out null windows and windows with null root nodes
            List<AccessibilityWindowInfo> validWindows = new ArrayList<>();
            for (AccessibilityWindowInfo window : windows) {
                if (window != null) {
                    // Log window details
                    Log.d(LOG_TAG, "Window type: " + window.getType() + 
                          ", isActive: " + window.isActive() + 
                          ", isFocused: " + window.isFocused() + 
                          ", hasRoot: " + (window.getRoot() != null));
                    
                    if (window.getRoot() != null) {
                        validWindows.add(window);
                    }
                } else {
                    Log.w(LOG_TAG, "Found null window");
                }
            }

            Log.d(LOG_TAG, "Valid windows for capture: " + validWindows.size());

            if (validWindows.isEmpty()) {
                Log.w(LOG_TAG, "No valid windows with root nodes available for capture");
                return;
            }

            TreeDebug.logNodeTrees(validWindows, _this);
            sendTree();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error during capture: " + e.getMessage(), e);
            // Send error response to client
            try {
                JSONObject errorJson = new JSONObject();
                errorJson.put("type", "captureError");
                errorJson.put("message", "Capture failed: " + e.getMessage());
                sendJSON(errorJson);
                sendTree();
            } catch (Exception sendError) {
                Log.e(LOG_TAG, "Failed to send error response: " + sendError.getMessage());
            }
        }
    }


    // Method to perform gesture actions using coordinates
    public void performGesture(String gestureType, float x, float y, float endX, float endY, int duration) {
        Log.d(LOG_TAG, "performGesture called with: type=" + gestureType + ", x=" + x + ", y=" + y + ", endX=" + endX + ", endY=" + endY + ", duration=" + duration);

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            Log.w(LOG_TAG, "Android version too low for gestures: " + android.os.Build.VERSION.SDK_INT);
            sendGestureResult(false, "Gesture actions require Android API 24 or higher");
            return;
        }

        Log.d(LOG_TAG, "Android version OK, proceeding with gesture");

        try {
            Log.d(LOG_TAG, "Creating GestureDescription.Builder");
            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            Path gesturePath = new Path();

            Log.d(LOG_TAG, "Processing gesture type: " + gestureType.toUpperCase());

            switch (gestureType.toUpperCase()) {
                case "TAP":
                case "CLICK":
                    Log.d(LOG_TAG, "Creating TAP gesture at (" + x + ", " + y + ")");
                    gesturePath.moveTo(x, y);
                    int tapDuration = duration > 0 ? duration : 100;
                    Log.d(LOG_TAG, "TAP duration: " + tapDuration + "ms");
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, tapDuration));
                    break;

                case "LONG_PRESS":
                case "LONG_CLICK":
                    Log.d(LOG_TAG, "Creating LONG_PRESS gesture at (" + x + ", " + y + ")");
                    int longPressDuration = duration > 0 ? Math.max(duration, 500) : 1000;
                    Log.d(LOG_TAG, "LONG_PRESS duration: " + longPressDuration + "ms");
                    gesturePath.moveTo(x, y);
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, longPressDuration));
                    break;

                case "SCROLL":
                case "SWIPE":
                    Log.d(LOG_TAG, "Creating SCROLL gesture from (" + x + ", " + y + ") to (" + endX + ", " + endY + ")");
                    if (endX == 0 && endY == 0) {
                        Log.e(LOG_TAG, "End coordinates are required for scroll/swipe");
                        sendGestureResult(false, "End coordinates (endX, endY) are required for scroll/swipe gestures");
                        return;
                    }
                    gesturePath.moveTo(x, y);
                    gesturePath.lineTo(endX, endY);
                    int scrollDuration = duration > 0 ? duration : 300;
                    Log.d(LOG_TAG, "SCROLL duration: " + scrollDuration + "ms");
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, scrollDuration));
                    break;

                case "SCROLL_UP":
                    Log.d(LOG_TAG, "Creating SCROLL_UP gesture from (" + x + ", " + y + ")");
                    gesturePath.moveTo(x, y);
                    gesturePath.lineTo(x, y - 300);
                    int scrollUpDuration = duration > 0 ? duration : 300;
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, scrollUpDuration));
                    break;

                case "SCROLL_DOWN":
                    Log.d(LOG_TAG, "Creating SCROLL_DOWN gesture from (" + x + ", " + y + ")");
                    gesturePath.moveTo(x, y);
                    gesturePath.lineTo(x, y + 300);
                    int scrollDownDuration = duration > 0 ? duration : 300;
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, scrollDownDuration));
                    break;

                case "SCROLL_LEFT":
                    Log.d(LOG_TAG, "Creating SCROLL_LEFT gesture from (" + x + ", " + y + ")");
                    gesturePath.moveTo(x, y);
                    gesturePath.lineTo(x - 300, y);
                    int scrollLeftDuration = duration > 0 ? duration : 300;
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, scrollLeftDuration));
                    break;

                case "SCROLL_RIGHT":
                    Log.d(LOG_TAG, "Creating SCROLL_RIGHT gesture from (" + x + ", " + y + ")");
                    gesturePath.moveTo(x, y);
                    gesturePath.lineTo(x + 300, y);
                    int scrollRightDuration = duration > 0 ? duration : 300;
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, scrollRightDuration));
                    break;

                case "DOUBLE_TAP":
                    Log.d(LOG_TAG, "Creating DOUBLE_TAP gesture at (" + x + ", " + y + ")");
                    gesturePath.moveTo(x, y);
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(gesturePath, 0, 50));

                    Path secondTapPath = new Path();
                    secondTapPath.moveTo(x, y);
                    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(secondTapPath, 150, 50));
                    break;

                default:
                    Log.e(LOG_TAG, "Unknown gesture type: " + gestureType);
                    sendGestureResult(false, "Unknown gesture type: " + gestureType);
                    return;
            }

            Log.d(LOG_TAG, "Building gesture description");
            GestureDescription gesture = gestureBuilder.build();

            Log.d(LOG_TAG, "Creating gesture result callback");
            GestureResultCallback gestureCallback = new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    Log.d(LOG_TAG, "Gesture completed successfully");
                    sendGestureResult(true, gestureType + " gesture completed successfully at (" + x + ", " + y + ")");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    Log.w(LOG_TAG, "Gesture was cancelled");
                    sendGestureResult(false, gestureType + " gesture was cancelled");
                }
            };

            Log.d(LOG_TAG, "Creating handler for main looper");
            Handler mainHandler = new Handler(Looper.getMainLooper());

            Log.d(LOG_TAG, "Dispatching gesture...");
            boolean result = dispatchGesture(gesture, gestureCallback, mainHandler);

            // Add timeout mechanism for debugging
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.w(LOG_TAG, "Gesture timeout - no callback received within 5 seconds");
                    // Note: We can't easily cancel the gesture callback, but we can send a timeout result
                    // Only send if we haven't already sent a result
                }
            }, 5000); // 5 second timeout

            Log.d(LOG_TAG, "dispatchGesture returned: " + result);

            if (!result) {
                Log.e(LOG_TAG, "Failed to dispatch gesture");
                sendGestureResult(false, "Failed to dispatch " + gestureType + " gesture");
            } else {
                Log.d(LOG_TAG, "Gesture dispatched successfully, waiting for callback");
            }

        } catch (Exception e) {
            String errorMessage = "Exception in performGesture: " + e.getMessage();
            Log.e(LOG_TAG, errorMessage, e);
            e.printStackTrace();
            sendGestureResult(false, errorMessage);
        } catch (Error e) {
            String errorMessage = "Error in performGesture: " + e.getMessage();
            Log.e(LOG_TAG, errorMessage, e);
            e.printStackTrace();
            sendGestureResult(false, errorMessage);
        }
    }
    // Send gesture result back to the client
    public void sendGestureResult(boolean success, String message) {
        Log.d(LOG_TAG, "sendGestureResult called: success=" + success + ", message=" + message);
        try {
            JSONObject resultJson = new JSONObject();
            resultJson.put("type", "gestureResult");
            resultJson.put("success", success);
            resultJson.put("message", message);

            Log.d(LOG_TAG, "Creating result intent for gesture result");
            Intent resultIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);

            Log.d(LOG_TAG, "Compressing gesture result JSON: " + resultJson.toString());
            resultIntent.putExtra("messageData", resultJson.toString());

            Log.d(LOG_TAG, "Starting service to send gesture result");
            startService(resultIntent);

            Log.d(LOG_TAG, "Gesture result sent successfully: " + message);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending gesture result: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
}
