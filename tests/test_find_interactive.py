#!/usr/bin/env python3
"""
Interactive test script for findByViewId and findByText commands
Allows you to search for specific view IDs or text interactively
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
            
            # Check if this is the response we're looking for
            if response_json.get("type") == expected_type:
                return response_json
            
            # Check if we've timed out
            if asyncio.get_event_loop().time() - start_time > timeout:
                raise asyncio.TimeoutError(f"Timeout waiting for {expected_type}")
                
            # Log that we're skipping an event
            event_type = response_json.get("type", "unknown")
            print(f"  (Skipping {event_type} event...)")
            
        except asyncio.TimeoutError:
            if asyncio.get_event_loop().time() - start_time > timeout:
                raise asyncio.TimeoutError(f"Timeout waiting for {expected_type}")
            continue
        except json.JSONDecodeError:
            # Skip non-JSON responses (like tree data)
            continue

async def interactive_test():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            print("\nInteractive Find Test")
            print("Commands:")
            print("  1. Find by View ID")
            print("  2. Find by Text")
            print("  3. Capture tree (to see what's available)")
            print("  4. Exit")
            
            while True:
                choice = input("\nEnter choice (1-4): ").strip()
                
                if choice == "1":
                    view_id = input("Enter view ID (e.g., com.example.app:id/button): ").strip()
                    if view_id:
                        msg = {
                            "message": "findByViewId",
                            "viewId": view_id
                        }
                        print(f"\nSending: {json.dumps(msg)}")
                        await websocket.send(json.dumps(msg))
                        
                        # Wait for the specific response, filtering out accessibility events
                        response_json = await wait_for_response_type(websocket, "findResult")
                        print_response(response_json)
                
                elif choice == "2":
                    text = input("Enter text to search: ").strip()
                    if text:
                        msg = {
                            "message": "findByText",
                            "text": text
                        }
                        print(f"\nSending: {json.dumps(msg)}")
                        await websocket.send(json.dumps(msg))
                        
                        # Wait for the specific response, filtering out accessibility events
                        response_json = await wait_for_response_type(websocket, "findResult")
                        print_response(response_json)
                
                elif choice == "3":
                    msg = {"message": "capture"}
                    print("\nCapturing tree...")
                    await websocket.send(json.dumps(msg))
                    
                    response = await websocket.recv()
                    print("Tree captured. Use the Inspector App to view details.")
                
                elif choice == "4":
                    print("Exiting...")
                    break
                
                else:
                    print("Invalid choice. Please enter 1-4.")
            
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

def print_response(response_json):
    """Pretty print the response"""
    print(f"\n{'='*50}")
    print(f"Response Type: {response_json.get('type')}")
    print(f"Success: {response_json.get('success')}")
    
    if not response_json.get('success'):
        print(f"Error: {response_json.get('message')}")
        return
    
    count = response_json.get('count', 0)
    print(f"Found: {count} node(s)")
    
    if count > 0:
        nodes = response_json.get('nodes', [])
        for i, node in enumerate(nodes):
            print(f"\n{i+1}. {node.get('className')}")
            print(f"   Text: '{node.get('text')}'")
            print(f"   Content Description: '{node.get('contentDescription')}'")
            print(f"   View ID: {node.get('viewIdResourceName')}")
            print(f"   Bounds: {node.get('boundsInScreen')}")
            print(f"   Clickable: {node.get('isClickable')}")
            print(f"   Enabled: {node.get('isEnabled')}")
            print(f"   Hash Code: {node.get('hashCode')}")
            
            # Additional properties that might be useful
            if node.get('isCheckable'):
                print(f"   Checkable: {node.get('isCheckable')} (Checked: {node.get('isChecked')})")
            if node.get('isFocusable'):
                print(f"   Focusable: {node.get('isFocusable')} (Focused: {node.get('isFocused')})")
            if node.get('isScrollable'):
                print(f"   Scrollable: {node.get('isScrollable')}")
            if node.get('isSelected'):
                print(f"   Selected: {node.get('isSelected')}")

if __name__ == "__main__":
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    asyncio.run(interactive_test())