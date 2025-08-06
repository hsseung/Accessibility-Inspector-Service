// app/src/main/java/com/jwlilly/accessibilityinspector/SocketService.java
package com.jwlilly.accessibilityinspector;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class SocketService extends Service {
    AsyncHttpServer server;
    private final int PORT = 38301;
    private SocketRequestCallback requestCallback;

    private Context context = this;

    private final String CHANNEL_ID = "AccessibilityInspectorChannel";
    public static String BROADCAST_MESSAGE = "broadcast";

    // Static reference to accessibility service instance
    private static AccessibilityInspector accessibilityServiceInstance;

    // Method to set the accessibility service instance
    public static void setAccessibilityServiceInstance(AccessibilityInspector instance) {
        accessibilityServiceInstance = instance;
        Log.d("SocketService", "AccessibilityInspector instance " + (instance != null ? "set" : "cleared"));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if(server == null) {
            server = new AsyncHttpServer();
            requestCallback = new SocketRequestCallback();
            Toast.makeText(context, "Inspector Service Created", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "Inspector Service already running", Toast.LENGTH_LONG).show();
        }
        try{
            server.listen(AsyncServer.getDefault(), PORT);
            server.websocket("/", requestCallback);
            Toast.makeText(this, "Inspector Service Started", Toast.LENGTH_LONG).show();
        } catch(Error e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent == null) {
            return START_NOT_STICKY;
        }
        String input = intent.getStringExtra("inputExtra");
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, ServiceActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Android Accessibility Inspector")
                .setContentText(input)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
        String action = intent.getAction();

        if (action == null) {
            return START_NOT_STICKY;
        } else if (action.equals(BROADCAST_MESSAGE)) {
            Log.d("SERVER", "SocketService received BROADCAST_MESSAGE intent");
            String messageData = intent.getStringExtra("messageData");
            Log.d("SERVER", "Message data: " + (messageData != null ? messageData.substring(0, Math.min(50, messageData.length())) + "..." : "null"));
            if (messageData != null) {
                requestCallback.BroadcastMessage(messageData);
            } else {
                Log.w("SERVER", "Received BROADCAST_MESSAGE with null messageData");
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Toast.makeText(this, "Inspector Service Stopped", Toast.LENGTH_LONG).show();
        server.stop();
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);
    }

    private class SocketRequestCallback implements AsyncHttpServer.WebSocketRequestCallback {
        ArrayList<WebSocket> _sockets = new ArrayList<>();
        @Override
        public void onConnected(WebSocket webSocket, AsyncHttpServerRequest request) {
            _sockets.add(webSocket);
            Log.d("SERVER", "client connected");
            webSocket.setStringCallback(s -> {
                if(s != null) {
                    try{
                        JSONObject jsonObject = new JSONObject(s);
                        // Add this right after the JSON parsing, before any other message handling
                        Log.d("SERVER", "Received JSON: " + jsonObject.toString());
                        Log.d("SERVER", "Message field: '" + jsonObject.optString("message", "NOT_FOUND") + "'");

                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("capture")) {
                            // Cancel any pending stability capture to avoid collision
                            if (accessibilityServiceInstance != null) {
                                accessibilityServiceInstance.cancelPendingStabilityCapture();
                            }
                            Intent intent = new Intent("com.jwlilly.accessibilityinspector");
                            intent.setAction("A11yInspector");
                            // Add optional visibleOnly parameter (defaults to false for backward compatibility)
                            boolean visibleOnly = jsonObject.optBoolean("visibleOnly", false);
                            intent.putExtra("visibleOnly", visibleOnly);
                            sendBroadcast(intent);
                        }
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("ping")) {
                            JSONObject pongObject = new JSONObject();
                            pongObject.put("message", "pong");
                            webSocket.send(pongObject.toString());
                        }
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("captureNotImportant")) {
                            // Cancel any pending stability capture to avoid collision
                            if (accessibilityServiceInstance != null) {
                                accessibilityServiceInstance.cancelPendingStabilityCapture();
                            }
                            Intent intent = new Intent("com.jwlilly.accessibilityinspector");
                            intent.setAction("A11yInspectorImportant");
                            // Add optional visibleOnly parameter (defaults to false for backward compatibility)
                            boolean visibleOnly = jsonObject.optBoolean("visibleOnly", false);
                            intent.putExtra("visibleOnly", visibleOnly);
                            sendBroadcast(intent);
                        }
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("performAction")) {
                            String resourceId = jsonObject.optString("resourceId", null);
                            String hashCode = jsonObject.optString("hashCode", null);
                            String action = jsonObject.optString("action", "");
                            String text = jsonObject.optString("text", null);

                            // Validate that we have either resourceId or hashCode, and an action
                            if ((resourceId != null && !resourceId.isEmpty()) || (hashCode != null && !hashCode.isEmpty())) {
                                if (!action.isEmpty()) {
                                    Intent actionIntent = new Intent("com.jwlilly.accessibilityinspector");
                                    actionIntent.setAction("A11yInspectorAction");

                                    if (resourceId != null && !resourceId.isEmpty()) {
                                        actionIntent.putExtra("resourceId", resourceId);
                                    }
                                    if (hashCode != null && !hashCode.isEmpty()) {
                                        actionIntent.putExtra("hashCode", hashCode);
                                    }

                                    actionIntent.putExtra("action", action);
                                    if (text != null && !text.isEmpty()) {
                                        actionIntent.putExtra("text", text);
                                    }
                                    sendBroadcast(actionIntent);
                                } else {
                                    // Send error response for missing action
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "actionResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: action");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                // Send error response for missing node identifier
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "actionResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Missing required parameter: either resourceId or hashCode must be provided");
                                webSocket.send(errorResponse.toString());
                            }
                        }
                        // Handle launch activity requests - DIRECT METHOD CALL
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("launchActivity")) {
                            Log.d("SERVER", "Processing launchActivity request");

                            if (accessibilityServiceInstance != null) {
                                String launchType = jsonObject.optString("launchType", "");
                                String packageName = jsonObject.optString("packageName", null);
                                String className = jsonObject.optString("className", null);
                                String intentAction = jsonObject.optString("intentAction", null);
                                String data = jsonObject.optString("data", null);
                                String category = jsonObject.optString("category", null);
                                String extras = jsonObject.optString("extras", null);

                                Log.d("SERVER", "Launch parameters - Type: " + launchType + ", Package: " + packageName);

                                if (!launchType.isEmpty()) {
                                    // Direct method call instead of broadcast
                                    accessibilityServiceInstance.launchActivity(launchType, packageName, className, intentAction, data, category, extras);
                                } else {
                                    Log.w("SERVER", "Empty launch type provided");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "launchResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: launchType");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "launchResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }

                        // Handle gesture requests - DIRECT METHOD CALL
                        if(jsonObject.has("message")) {
                            String messageValue = jsonObject.getString("message");

                            if(messageValue.equalsIgnoreCase("performGesture")) {
                                Log.d("SERVER", "Processing performGesture request");

                                if (accessibilityServiceInstance != null) {
                                    Log.d("SERVER", "AccessibilityService instance is available");

                                    String gestureType = jsonObject.optString("gestureType", "");
                                    float x = (float) jsonObject.optDouble("x", 0);
                                    float y = (float) jsonObject.optDouble("y", 0);
                                    float endX = (float) jsonObject.optDouble("endX", 0);
                                    float endY = (float) jsonObject.optDouble("endY", 0);
                                    int duration = jsonObject.optInt("duration", 0);

                                    Log.d("SERVER", "Gesture parameters - Type: '" + gestureType + "', Coordinates: (" + x + ", " + y + ")");
                                    Log.d("SERVER", "End coordinates: (" + endX + ", " + endY + "), Duration: " + duration);

                                    if (!gestureType.isEmpty() && x >= 0 && y >= 0) {
                                        Log.d("SERVER", "Parameters valid, calling performGesture");
                                        // Direct method call
                                        accessibilityServiceInstance.performGesture(gestureType, x, y, endX, endY, duration);
                                    } else {
                                        Log.w("SERVER", "Invalid gesture parameters - gestureType: '" + gestureType + "', x: " + x + ", y: " + y);
                                        JSONObject errorResponse = new JSONObject();
                                        errorResponse.put("type", "gestureResult");
                                        errorResponse.put("success", false);
                                        errorResponse.put("message", "Missing or invalid required parameters: gestureType, x, and y coordinates are required");
                                        webSocket.send(errorResponse.toString());
                                    }
                                } else {
                                    Log.e("SERVER", "AccessibilityInspector instance not available for gesture");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "gestureResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Accessibility service not available");
                                    webSocket.send(errorResponse.toString());
                                }
                            }
                        }

                        // Handle findByRegex (custom regex implementation)
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("findByRegex")) {
                            Log.d("SERVER", "Processing findByRegex request");
                            
                            if (accessibilityServiceInstance != null) {
                                String pattern = jsonObject.optString("pattern", null);
                                
                                if (pattern != null && !pattern.isEmpty()) {
                                    boolean verbose = jsonObject.optBoolean("verbose", false);
                                    // Direct method call to regex implementation
                                    accessibilityServiceInstance.findByRegex(pattern, verbose);
                                } else {
                                    Log.w("SERVER", "Missing pattern parameter");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "findResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: pattern");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "findResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }

                        // Handle findByViewId
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("findByViewId")) {
                            Log.d("SERVER", "Processing findByViewId request");
                            
                            if (accessibilityServiceInstance != null) {
                                String viewId = jsonObject.optString("viewId", null);
                                
                                if (viewId != null && !viewId.isEmpty()) {
                                    boolean verbose = jsonObject.optBoolean("verbose", false);
                                    // Direct method call
                                    accessibilityServiceInstance.findByViewId(viewId, verbose);
                                } else {
                                    Log.w("SERVER", "Missing viewId parameter");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "findResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: viewId");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "findResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }

                        // Handle findByText
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("findByText")) {
                            Log.d("SERVER", "Processing findByText request");
                            
                            if (accessibilityServiceInstance != null) {
                                String text = jsonObject.optString("text", null);
                                boolean verbose = jsonObject.optBoolean("verbose", false);
                                
                                if (text != null && !text.isEmpty()) {
                                    // Direct method call
                                    accessibilityServiceInstance.findByText(text, verbose);
                                } else {
                                    Log.w("SERVER", "Missing text parameter");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "findResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: text");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "findResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }

                        // Handle customFindByText (custom recursive implementation)
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("customFindByText")) {
                            Log.d("SERVER", "Processing customFindByText request");
                            
                            if (accessibilityServiceInstance != null) {
                                String text = jsonObject.optString("text", null);
                                
                                if (text != null && !text.isEmpty()) {
                                    boolean verbose = jsonObject.optBoolean("verbose", false);
                                    // Direct method call to custom implementation
                                    accessibilityServiceInstance.customFindByText(text, verbose);
                                } else {
                                    Log.w("SERVER", "Missing text parameter");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "findResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: text");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "findResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }

                        // Handle customFindByViewId (custom recursive implementation)
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("customFindByViewId")) {
                            Log.d("SERVER", "Processing customFindByViewId request");
                            
                            if (accessibilityServiceInstance != null) {
                                String viewId = jsonObject.optString("viewId", null);
                                
                                if (viewId != null && !viewId.isEmpty()) {
                                    boolean verbose = jsonObject.optBoolean("verbose", false);
                                    // Direct method call to custom implementation
                                    accessibilityServiceInstance.customFindByViewId(viewId, verbose);
                                } else {
                                    Log.w("SERVER", "Missing viewId parameter");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "findResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: viewId");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "findResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }

                        // Handle findByProps (property-based search)
                        if(jsonObject.has("message") && jsonObject.getString("message").equalsIgnoreCase("findByProps")) {
                            Log.d("SERVER", "Processing findByProps request");
                            
                            if (accessibilityServiceInstance != null) {
                                JSONObject properties = jsonObject.optJSONObject("properties");
                                
                                if (properties != null) {
                                    boolean verbose = jsonObject.optBoolean("verbose", false);
                                    // Direct method call to properties implementation
                                    accessibilityServiceInstance.findByProps(properties, verbose);
                                } else {
                                    Log.w("SERVER", "Missing properties parameter");
                                    JSONObject errorResponse = new JSONObject();
                                    errorResponse.put("type", "findResult");
                                    errorResponse.put("success", false);
                                    errorResponse.put("message", "Missing required parameter: properties");
                                    webSocket.send(errorResponse.toString());
                                }
                            } else {
                                Log.e("SERVER", "AccessibilityInspector instance not available");
                                JSONObject errorResponse = new JSONObject();
                                errorResponse.put("type", "findResult");
                                errorResponse.put("success", false);
                                errorResponse.put("message", "Accessibility service not available");
                                webSocket.send(errorResponse.toString());
                            }
                        }
                    } catch(JSONException e) {
                        Log.d("ERROR", e.getMessage());
                        try {
                            JSONObject errorResponse = new JSONObject();
                            errorResponse.put("type", "error");
                            errorResponse.put("message", "Invalid JSON format: " + e.getMessage());
                            webSocket.send(errorResponse.toString());
                        } catch (JSONException jsonException) {
                            Log.e("ERROR", "Failed to send error response", jsonException);
                        }
                    }
                }
                Log.d("SERVER", s);
            });

            webSocket.setClosedCallback(ex -> {
                try {
                    if (ex != null)
                        Log.e("SERVER", "An error occurred", ex);
                } finally {
                    Log.d("SERVER", "closed");
                    _sockets.remove(webSocket);
                }
            });

            webSocket.setEndCallback(ex -> Log.d("SERVER", "ended: " + ex.getMessage()));
        }

        public void BroadcastMessage(String message) {
            Log.d("SERVER", "Broadcasting message to " + _sockets.size() + " clients: " + message.substring(0, Math.min(100, message.length())) + "...");
            for (WebSocket socket : _sockets)
                socket.send(message);
        }
    }

}


