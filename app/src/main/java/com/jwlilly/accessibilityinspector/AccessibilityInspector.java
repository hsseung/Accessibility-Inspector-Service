// app/src/main/java/com/jwlilly/accessibilityinspector/AccessibilityInspector.java
package com.jwlilly.accessibilityinspector;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;


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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            List<CharSequence> list = event.getText();
            for(CharSequence charSequence : list) {
                if(charSequence.toString().trim().length() > 0) {
                    sendAnnouncement(charSequence.toString());
                }
            }
        }
//        if(event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
//            try {
//                AccessibilityNodeInfo info = event.getSource();
//                boolean systemWindow = event.getPackageName().toString().contains(".android");
//                if (!systemWindow && info != null && info.getWindow().isActive() && info.getParent() != null) {
//                    do {
//                        info = info.getParent();
//                        Log.d("TAG", info.toString());
//                    } while (info.getParent() != null);
//                    lastKnown = info;
//                }
//            } catch (Exception e) {
//                Log.d("AccessibilityInspector", "null parent");
//            }
//
//        }
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
                | AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT;
        info.eventTypes = AccessibilityEvent.TYPE_ANNOUNCEMENT;
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
                Log.d(LOG_TAG, "Processing capture request (important only)");
                hideNotImportant();
                startCapture();
            } else if(intent.getAction().equalsIgnoreCase("A11yInspectorImportant")) {
                Log.d(LOG_TAG, "Processing capture request (all)");
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
                List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY);

                if (resolveInfos.isEmpty()) {
                    sendLaunchResult(false, "No app found to handle " + launchDescription);
                    return;
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
            SocketService.data = compress(resultJson.toString());
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
            SocketService.data = compress(resultJson.toString());
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
            jsonObject.put("name", "");
            Intent announcementIntent = new Intent(SocketService.BROADCAST_MESSAGE, null, this, SocketService.class);
            SocketService.data = compress(jsonObject.toString());
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
            SocketService.data = compress(announcementJson.toString());
            startService(announcementIntent);
            Log.d(LOG_TAG, "announcement sent");
        } catch (Exception e) {
            Log.e(LOG_TAG,e.getMessage());
        }
    }

    public void startCapture() {
        List<AccessibilityWindowInfo> windows = getWindows();

        TreeDebug.logNodeTrees(windows, _this);
        sendTree();
    }

    public static byte[] compress(String string) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(string.length());
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(string.getBytes());
        gos.close();
        byte[] compressed = os.toByteArray();
        os.close();
        return compressed;
    }
}
