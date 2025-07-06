package com.jwlilly.accessibilityinspector;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom recursive implementations of node finding to compare with Android's native methods
 */
public class CustomNodeFinder {
    
    /**
     * Recursively find all nodes with exact text match (case-sensitive) in either text or contentDescription
     */
    public static List<AccessibilityNodeInfo> findNodesByText(AccessibilityNodeInfo root, String searchText) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        if (root == null || searchText == null) {
            return results;
        }
        
        findNodesByTextRecursive(root, searchText, results);
        return results;
    }
    
    private static void findNodesByTextRecursive(AccessibilityNodeInfo node, String searchText, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        // Check text field (exact match, case-sensitive)
        CharSequence nodeText = node.getText();
        if (nodeText != null) {
            String textStr = nodeText.toString();
            if (textStr.equals(searchText)) {
                results.add(node);
            }
        }
        
        // Check content description (exact match, case-sensitive)
        CharSequence contentDesc = node.getContentDescription();
        if (contentDesc != null) {
            String descStr = contentDesc.toString();
            if (descStr.equals(searchText)) {
                // Only add if not already added from text match
                if (!results.contains(node)) {
                    results.add(node);
                }
            }
        }
        
        // Recursively search children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByTextRecursive(child, searchText, results);
            }
        }
    }
    
    /**
     * Recursively find all nodes matching regex pattern in either text or contentDescription
     */
    public static List<AccessibilityNodeInfo> findNodesByRegex(AccessibilityNodeInfo root, String regexPattern) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        if (root == null || regexPattern == null) {
            return results;
        }
        
        findNodesByRegexRecursive(root, regexPattern, results);
        return results;
    }
    
    private static void findNodesByRegexRecursive(AccessibilityNodeInfo node, String regexPattern, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        try {
            // Check text field
            CharSequence nodeText = node.getText();
            if (nodeText != null) {
                String textStr = nodeText.toString();
                if (textStr.matches(regexPattern)) {
                    results.add(node);
                }
            }
            
            // Check content description
            CharSequence contentDesc = node.getContentDescription();
            if (contentDesc != null) {
                String descStr = contentDesc.toString();
                if (descStr.matches(regexPattern)) {
                    // Only add if not already added from text match
                    if (!results.contains(node)) {
                        results.add(node);
                    }
                }
            }
        } catch (Exception e) {
            // Invalid regex - skip this node but continue searching
        }
        
        // Recursively search children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByRegexRecursive(child, regexPattern, results);
            }
        }
    }
    
    /**
     * Recursively find all nodes with the specified viewId
     */
    public static List<AccessibilityNodeInfo> findNodesByViewId(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        if (root == null || viewId == null) {
            return results;
        }
        
        findNodesByViewIdRecursive(root, viewId, results);
        return results;
    }
    
    private static void findNodesByViewIdRecursive(AccessibilityNodeInfo node, String viewId, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        // Check viewId
        String nodeViewId = node.getViewIdResourceName();
        if (nodeViewId != null && nodeViewId.equals(viewId)) {
            results.add(node);
        }
        
        // Recursively search children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByViewIdRecursive(child, viewId, results);
            }
        }
    }
    
    /**
     * Recursively find all nodes matching specified properties
     * Example properties: {"text": "Submit", "isClickable": true, "viewIdResourceName": "com.Slack:id/button"}
     */
    public static List<AccessibilityNodeInfo> findNodesByProps(AccessibilityNodeInfo root, JSONObject properties) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        if (root == null || properties == null) {
            return results;
        }
        
        findNodesByPropsRecursive(root, properties, results);
        return results;
    }
    
    private static void findNodesByPropsRecursive(AccessibilityNodeInfo node, JSONObject properties, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        boolean matches = true;
        int propertyCount = 0;
        
        // Check each property requirement
        java.util.Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            String property = keys.next();
            propertyCount++;
            try {
                Object expectedValue = properties.get(property);
                
                
                if (!nodeMatchesProperty(node, property, expectedValue)) {
                    matches = false;
                    break;
                }
            } catch (JSONException e) {
                matches = false;
                break;
            }
        }
        
        // Don't match if no properties were specified
        if (propertyCount == 0) {
            matches = false;
        }
        
        if (matches) {
            results.add(node);
        }
        
        // Recursively search children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByPropsRecursive(child, properties, results);
            }
        }
    }
    
    /**
     * Check if a node matches a specific property requirement
     */
    private static boolean nodeMatchesProperty(AccessibilityNodeInfo node, String property, Object expectedValue) {
        try {
            
            switch (property.toLowerCase()) {
                case "classname":
                    return matchesStringValue(node.getClassName(), expectedValue);
                case "text":
                    return matchesStringValue(node.getText(), expectedValue);
                case "contentdescription":
                    return matchesStringValue(node.getContentDescription(), expectedValue);
                case "viewid":
                case "viewidresourcename":
                case "resourceid":
                    return matchesStringValue(node.getViewIdResourceName(), expectedValue);
                case "isclickable":
                    return matchesBooleanValue(node.isClickable(), expectedValue);
                case "isenabled":
                    return matchesBooleanValue(node.isEnabled(), expectedValue);
                case "isfocusable":
                    return matchesBooleanValue(node.isFocusable(), expectedValue);
                case "isfocused":
                    return matchesBooleanValue(node.isFocused(), expectedValue);
                case "isscrollable":
                    return matchesBooleanValue(node.isScrollable(), expectedValue);
                case "ischeckable":
                    return matchesBooleanValue(node.isCheckable(), expectedValue);
                case "ischecked":
                    return matchesBooleanValue(node.isChecked(), expectedValue);
                case "isselected":
                    return matchesBooleanValue(node.isSelected(), expectedValue);
                case "childcount":
                    return matchesIntValue(node.getChildCount(), expectedValue);
                default:
                    // Unknown property - ignore (return true to continue matching other properties)
                    return true;
            }
        } catch (Exception e) {
            // Error checking property - assume no match
            return false;
        }
    }
    
    private static boolean matchesStringValue(CharSequence nodeValue, Object expectedValue) {
        if (nodeValue == null && expectedValue == null) return true;
        if (nodeValue == null || expectedValue == null) return false;
        
        String nodeStr = nodeValue.toString();
        String expectedStr = expectedValue.toString();
        
        return nodeStr.equals(expectedStr);
    }
    
    private static boolean matchesBooleanValue(boolean nodeValue, Object expectedValue) {
        if (expectedValue instanceof Boolean) {
            return nodeValue == (Boolean) expectedValue;
        } else if (expectedValue instanceof String) {
            return nodeValue == Boolean.parseBoolean((String) expectedValue);
        }
        return false;
    }
    
    private static boolean matchesIntValue(int nodeValue, Object expectedValue) {
        if (expectedValue instanceof Integer) {
            return nodeValue == (Integer) expectedValue;
        } else if (expectedValue instanceof String) {
            try {
                return nodeValue == Integer.parseInt((String) expectedValue);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Get all nodes in the tree (useful for debugging)
     */
    public static List<AccessibilityNodeInfo> getAllNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        if (root == null) return results;
        
        getAllNodesRecursive(root, results);
        return results;
    }
    
    private static void getAllNodesRecursive(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        results.add(node);
        
        // Recursively add children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                getAllNodesRecursive(child, results);
            }
        }
    }
    
    /**
     * Count total nodes in tree (for statistics)
     */
    public static int countNodes(AccessibilityNodeInfo root) {
        if (root == null) return 0;
        
        int count = 1; // Count this node
        
        // Count children
        int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                count += countNodes(child);
            }
        }
        
        return count;
    }
    
    /**
     * Get statistics about what's in the tree
     */
    public static String getTreeStats(AccessibilityNodeInfo root) {
        if (root == null) return "Root is null";
        
        List<AccessibilityNodeInfo> allNodes = getAllNodes(root);
        int totalNodes = allNodes.size();
        int nodesWithText = 0;
        int nodesWithContentDesc = 0;
        int nodesWithViewId = 0;
        int clickableNodes = 0;
        
        for (AccessibilityNodeInfo node : allNodes) {
            if (node.getText() != null && node.getText().length() > 0) {
                nodesWithText++;
            }
            if (node.getContentDescription() != null && node.getContentDescription().length() > 0) {
                nodesWithContentDesc++;
            }
            if (node.getViewIdResourceName() != null && node.getViewIdResourceName().length() > 0) {
                nodesWithViewId++;
            }
            if (node.isClickable()) {
                clickableNodes++;
            }
        }
        
        return String.format("Total nodes: %d, With text: %d, With contentDesc: %d, With viewId: %d, Clickable: %d",
                totalNodes, nodesWithText, nodesWithContentDesc, nodesWithViewId, clickableNodes);
    }
}