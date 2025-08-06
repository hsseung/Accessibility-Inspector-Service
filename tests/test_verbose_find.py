#!/usr/bin/env python3
"""
Test script to demonstrate verbose mode in find methods
"""

import asyncio
import websockets
import json

async def test_verbose():
    uri = "ws://localhost:38301/"
    
    async with websockets.connect(uri) as websocket:
        print("=== Testing Verbose Mode ===\n")
        
        # Test 1: Normal mode (verbose=false by default)
        print("1. Normal mode - findByViewId:")
        await websocket.send(json.dumps({
            "message": "findByViewId",
            "viewId": "com.android.systemui:id/clock"
        }))
        
        response = await websocket.recv()
        result = json.loads(response)
        
        if result.get("type") == "findResult" and result.get("count", 0) > 0:
            node = result["nodes"][0]
            print(f"  Properties returned: {len(node)} fields")
            print(f"  Fields: {', '.join(sorted(node.keys())[:5])}...")
        
        # Test 2: Verbose mode
        print("\n2. Verbose mode - findByViewId:")
        await websocket.send(json.dumps({
            "message": "findByViewId",
            "viewId": "com.android.systemui:id/clock",
            "verbose": True
        }))
        
        response = await websocket.recv()
        result = json.loads(response)
        
        if result.get("type") == "findResult" and result.get("count", 0) > 0:
            node = result["nodes"][0]
            print(f"  Properties returned: {len(node)} fields")
            print(f"  Additional verbose fields:")
            
            verbose_fields = [
                "hintText", "errorText", "tooltipText", "paneTitle", 
                "stateDescription", "roleDescription", "labeledByHashCode",
                "isLongClickable", "isVisibleToUser", "isImportantForAccessibility",
                "isContentInvalid", "isScreenReaderFocusable", 
                "collectionInfo", "collectionItemInfo", "actionList", 
                "windowId", "childCount"
            ]
            
            for field in verbose_fields:
                if field in node:
                    print(f"    - {field}: {node[field]}")
        
        # Test 3: Compare with customFindByText
        print("\n3. Verbose mode - customFindByText:")
        await websocket.send(json.dumps({
            "message": "customFindByText",
            "text": "Phone",
            "verbose": True
        }))
        
        response = await websocket.recv()
        result = json.loads(response)
        
        if result.get("type") == "findResult" and result.get("count", 0) > 0:
            print(f"  Found {result.get('count', 0)} nodes")
            node = result["nodes"][0]
            
            # Show some verbose properties if available
            if "actionList" in node and node["actionList"]:
                print(f"  Available actions:")
                for action in node["actionList"]:
                    print(f"    - ID: {action.get('id')}, Label: {action.get('label', 'No label')}")
            
            if "isVisibleToUser" in node:
                print(f"  Visible to user: {node['isVisibleToUser']}")
            
            if "childCount" in node:
                print(f"  Child count: {node['childCount']}")

if __name__ == "__main__":
    print("Verbose Mode Test")
    print("This demonstrates the additional properties available with verbose=true")
    print("Make sure to run: adb forward tcp:38301 tcp:38301\n")
    
    asyncio.run(test_verbose())