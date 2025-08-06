#!/usr/bin/env python3
"""
Test script for the new findByProps method
"""

import asyncio
import websockets
import json

async def test_find_by_props():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("=== Testing findByProps Method ===")
            print("Connected to Accessibility Inspector Service")
            
            # Test 1: Find elements with text 'may' and specific viewId
            print("\n1. Finding elements with text 'may' and viewId 'com.Slack:id/text_primary':")
            await websocket.send(json.dumps({
                "message": "findByProps",
                "properties": {
                    "text": "may",
                    "viewIdResourceName": "com.Slack:id/text_primary"
                }
            }))
            
            response = await websocket.recv()
            try:
                result = json.loads(response)
                if result.get("type") == "findResult":
                    print(f"✅ Found {result.get('count', 0)} elements matching both criteria")
                    if result.get('count', 0) > 0:
                        for i, node in enumerate(result.get('nodes', [])):
                            print(f"  Node {i+1} details:")
                            print(f"    Text: '{node.get('text', 'No text')}'")
                            print(f"    ViewId: {node.get('resourceId', 'No resourceId')}")
                            print(f"    ClassName: {node.get('className', 'Unknown class')}")
                            print(f"    IsClickable: {node.get('isClickable', False)}")
                            print(f"    IsEnabled: {node.get('isEnabled', False)}")
                            print(f"    HashCode: {node.get('hashCode', 'No hashCode')}")
                            bounds = node.get('boundsInScreen', {})
                            if bounds:
                                print(f"    Bounds: ({bounds.get('left', 0)}, {bounds.get('top', 0)}) - ({bounds.get('right', 0)}, {bounds.get('bottom', 0)})")
                            print(f"    Full node: {json.dumps(node, indent=2)}")
                            print()
                else:
                    print(f"❌ Unexpected response: {result}")
            except json.JSONDecodeError:
                print(f"❌ Could not parse response: {response}")
            
            # Test 2: Find elements with specific viewId and enabled state
            print("\n2. Finding enabled Slack FAB button:")
            await websocket.send(json.dumps({
                "message": "findByProps", 
                "properties": {
                    "viewIdResourceName": "com.Slack:id/creation_fab",
                    "isEnabled": True
                }
            }))
            
            response = await websocket.recv()
            try:
                result = json.loads(response)
                if result.get("type") == "findResult":
                    print(f"✅ Found {result.get('count', 0)} enabled Slack FAB elements")
                    if result.get('count', 0) > 0:
                        for i, node in enumerate(result.get('nodes', [])):
                            print(f"  Node {i+1}: {node.get('resourceId', 'No resourceId')} - clickable: {node.get('isClickable', False)}")
                else:
                    print(f"❌ Unexpected response: {result}")
            except json.JSONDecodeError:
                print(f"❌ Could not parse response: {response}")
            
            # Test 3: Find buttons by class with multiple criteria
            print("\n3. Finding Button elements that are both clickable and enabled:")
            await websocket.send(json.dumps({
                "message": "findByProps",
                "properties": {
                    "className": "android.widget.Button",
                    "isClickable": True,
                    "isEnabled": True
                }
            }))
            
            response = await websocket.recv()
            try:
                result = json.loads(response)
                if result.get("type") == "findResult":
                    print(f"✅ Found {result.get('count', 0)} clickable enabled Button elements")
                    if result.get('count', 0) > 0:
                        for i, node in enumerate(result.get('nodes', [])):
                            text = node.get('text', '')
                            resource_id = node.get('resourceId', 'No resourceId')
                            print(f"  Node {i+1}: '{text}' - {resource_id}")
                else:
                    print(f"❌ Unexpected response: {result}")
            except json.JSONDecodeError:
                print(f"❌ Could not parse response: {response}")
            
            # Test 4: Error handling - missing properties
            print("\n4. Testing error handling (missing properties):")
            await websocket.send(json.dumps({
                "message": "findByProps"
                # No properties field
            }))
            
            response = await websocket.recv()
            try:
                result = json.loads(response)
                if result.get("type") == "findResult" and not result.get("success", True):
                    print(f"✅ Error handled correctly: {result.get('message', 'Unknown error')}")
                else:
                    print(f"❌ Expected error response, got: {result}")
            except json.JSONDecodeError:
                print(f"❌ Could not parse error response: {response}")
            
            print("\n=== findByProps Test Complete ===")
            print("Method signature: {\"message\": \"findByProps\", \"properties\": {\"key\": \"value\", ...}}")
            print("Supported property types:")
            print("  - String: text, contentDescription, className, viewIdResourceName, resourceId, viewId")
            print("  - Boolean: isClickable, isEnabled, isFocusable, isFocused, isScrollable, isCheckable, isChecked, isSelected") 
            print("  - Integer: childCount")
            
    except Exception as e:
        print(f"Connection error: {e}")
        print("Make sure:")
        print("1. Accessibility service is enabled")
        print("2. Port forwarding is active: adb forward tcp:38301 tcp:38301")
        print("3. Device/emulator is connected")

if __name__ == "__main__":
    print("findByProps Test Script")
    print("This tests the new property-based search functionality")
    print("Example: Find all clickable buttons with text 'Submit'")
    
    asyncio.run(test_find_by_props())
