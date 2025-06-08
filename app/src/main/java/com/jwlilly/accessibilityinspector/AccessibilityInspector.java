package com.jwlilly.accessibilityinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
        super.onDestroy();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
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
                hideNotImportant();
                startCapture();
            } else if(intent.getAction().equalsIgnoreCase("A11yInspectorImportant")) {
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
