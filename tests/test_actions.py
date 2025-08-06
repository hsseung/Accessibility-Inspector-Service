#!/usr/bin/env python3
"""
Test script for performAction commands
"""

import asyncio
import websockets
import json
import sys

async def wait_for_response_type(websocket, expected_type, timeout=10):
    """Wait for a specific response type, filtering out accessibility events"""
    start_time = asyncio.get_event_loop().time()
    
    while True:
        try:
            response = await asyncio.wait_for(websocket.recv(), timeout=2.0)
            response_json = json.loads(response)
            
            if response_json.get("type") == expected_type:
                return response_json
            
            if asyncio.get_event_loop().time() - start_time > timeout:
                raise asyncio.TimeoutError(f"Timeout waiting for {expected_type}")
                
        except asyncio.TimeoutError:
            if asyncio.get_event_loop().time() - start_time > timeout:
                raise asyncio.TimeoutError(f"Timeout waiting for {expected_type}")
            continue
        except json.JSONDecodeError:
            continue

async def test_actions():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            print("\n=== Testing Action Commands ===")
            
            # First, find some clickable elements to test with
            print("\n1. Finding clickable elements...")
            
            # Search for common clickable elements
            search_terms = [
                {"message": "findByText", "text": "Settings"},
                {"message": "findByText", "text": "Home"},
                {"message": "findByText", "text": "Back"},
                {"message": "findByText", "text": "OK"},
                {"message": "findByText", "text": "Cancel"},
                {"message": "findByViewId", "viewId": "android:id/home"},
                {"message": "findByViewId", "viewId": "android:id/back"},
                {"message": "findByViewId", "viewId": "com.android.launcher3:id/apps_view"}
            ]
            
            test_elements = []
            for search in search_terms:
                print(f"Searching for: {search}")
                await websocket.send(json.dumps(search))
                response_json = await wait_for_response_type(websocket, "findResult")
                
                if response_json.get('count', 0) > 0:
                    nodes = response_json.get('nodes', [])
                    for node in nodes:
                        # Only add clickable elements
                        if node.get('isClickable', False):
                            test_elements.append({
                                "hashCode": str(node.get('hashCode')),
                                "viewId": node.get('viewIdResourceName', ''),
                                "description": f"{node.get('className')} - '{node.get('text')}' (clickable={node.get('isClickable')})"
                            })
                            print(f"Found clickable element: {test_elements[-1]['description']}")
                            break  # Only take first clickable element from each search
                
                if len(test_elements) >= 3:  # Limit to 3 elements
                    break
            
            if len(test_elements) == 0:
                print("No clickable elements found. Testing with dummy element...")
                test_elements = [
                    {"hashCode": "123456", "description": "dummy element (will fail)"}
                ]
            else:
                print(f"\nSelected {len(test_elements)} clickable elements for testing")
            
            # Test various action types
            actions_to_test = [
                {"action": "CLICK", "description": "Click action"},
                {"action": "FOCUS", "description": "Focus action"},
                {"action": "ACCESSIBILITY_FOCUS", "description": "Accessibility focus"},
                {"action": "LONG_CLICK", "description": "Long click action"},
            ]
            
            for element in test_elements:
                print(f"\n--- Testing with: {element['description']} ---")
                
                for action_test in actions_to_test:
                    print(f"\n2. Testing {action_test['description']}...")
                    
                    # Test by hashCode
                    action_msg = {
                        "message": "performAction",
                        "hashCode": element["hashCode"],
                        "action": action_test["action"]
                    }
                    
                    print(f"Sending: {json.dumps(action_msg)}")
                    await websocket.send(json.dumps(action_msg))
                    
                    try:
                        response_json = await wait_for_response_type(websocket, "actionResult", timeout=5)
                        success = response_json.get('success')
                        message = response_json.get('message')
                        print(f"Result: {'✓' if success else '✗'} {message}")
                    except asyncio.TimeoutError:
                        print("✗ Timeout waiting for action result")
                    
                    # If element has viewId, test by viewId too
                    if element.get('viewId'):
                        action_msg = {
                            "message": "performAction",
                            "resourceId": element["viewId"],
                            "action": action_test["action"]
                        }
                        
                        print(f"Sending (by viewId): {json.dumps(action_msg)}")
                        await websocket.send(json.dumps(action_msg))
                        
                        try:
                            response_json = await wait_for_response_type(websocket, "actionResult", timeout=5)
                            success = response_json.get('success')
                            message = response_json.get('message')
                            print(f"Result: {'✓' if success else '✗'} {message}")
                        except asyncio.TimeoutError:
                            print("✗ Timeout waiting for action result")
                
                # Test SET_TEXT action
                print(f"\n3. Testing SET_TEXT action...")
                action_msg = {
                    "message": "performAction",
                    "hashCode": element["hashCode"],
                    "action": "SET_TEXT",
                    "text": "Test input"
                }
                
                print(f"Sending: {json.dumps(action_msg)}")
                await websocket.send(json.dumps(action_msg))
                
                try:
                    response_json = await wait_for_response_type(websocket, "actionResult", timeout=5)
                    success = response_json.get('success')
                    message = response_json.get('message')
                    print(f"Result: {'✓' if success else '✗'} {message}")
                except asyncio.TimeoutError:
                    print("✗ Timeout waiting for action result")
                
                break  # Only test with first element for now
            
            # Test error cases
            print(f"\n--- Testing Error Cases ---")
            
            # Missing action
            error_msg = {
                "message": "performAction",
                "hashCode": "123456"
                # Missing action parameter
            }
            print(f"\n4. Testing missing action parameter...")
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "actionResult", timeout=5)
                success = response_json.get('success')
                message = response_json.get('message')
                print(f"Result: {'✓' if success else '✗'} {message}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # Missing element identifier
            error_msg = {
                "message": "performAction",
                "action": "CLICK"
                # Missing hashCode and resourceId
            }
            print(f"\n5. Testing missing element identifier...")
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "actionResult", timeout=5)
                success = response_json.get('success')
                message = response_json.get('message')
                print(f"Result: {'✓' if success else '✗'} {message}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            print("\n=== Action Tests Completed ===")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    print("Testing performAction commands...")
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    asyncio.run(test_actions())