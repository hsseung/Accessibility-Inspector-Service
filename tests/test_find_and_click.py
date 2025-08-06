#!/usr/bin/env python3
"""
Test script that demonstrates finding elements and then performing actions on them
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
                
            # Skip accessibility events silently in this test
            
        except asyncio.TimeoutError:
            if asyncio.get_event_loop().time() - start_time > timeout:
                raise asyncio.TimeoutError(f"Timeout waiting for {expected_type}")
            continue
        except json.JSONDecodeError:
            # Skip non-JSON responses (like tree data)
            continue

async def find_and_click_test():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            
            # Example: Find a button by text and click it
            print("\n=== Find and Click Example ===")
            
            # Step 1: Find elements with specific text
            search_text = input("Enter text to search for (e.g., 'Settings', 'Home'): ").strip()
            if not search_text:
                search_text = "Settings"
            
            find_msg = {
                "message": "findByText",
                "text": search_text
            }
            print(f"\nSearching for: '{search_text}'")
            await websocket.send(json.dumps(find_msg))
            
            # Wait for the specific response, filtering out accessibility events
            response_json = await wait_for_response_type(websocket, "findResult")
            
            if not response_json.get('success'):
                print(f"Error: {response_json.get('message')}")
                return
            
            nodes = response_json.get('nodes', [])
            count = response_json.get('count', 0)
            
            if count == 0:
                print(f"No elements found with text '{search_text}'")
                return
            
            print(f"\nFound {count} element(s):")
            clickable_nodes = []
            
            for i, node in enumerate(nodes):
                print(f"\n{i+1}. {node.get('className')}")
                print(f"   Text: '{node.get('text')}'")
                print(f"   Content Description: '{node.get('contentDescription')}'")
                print(f"   View ID: {node.get('viewIdResourceName')}")
                print(f"   Bounds: {node.get('boundsInScreen')}")
                print(f"   Clickable: {node.get('isClickable')}")
                print(f"   Enabled: {node.get('isEnabled')}")
                print(f"   Hash Code: {node.get('hashCode')}")
                
                if node.get('isClickable'):
                    clickable_nodes.append(node)
            
            if not clickable_nodes:
                print("\nNo clickable elements found.")
                return
            
            # Step 2: Select which node to click
            print(f"\nFound {len(clickable_nodes)} clickable element(s)")
            
            if len(clickable_nodes) == 1:
                target_node = clickable_nodes[0]
                print(f"Clicking on: {target_node.get('text')}")
            else:
                print("\nMultiple clickable elements found. Select one:")
                for i, node in enumerate(clickable_nodes):
                    text = node.get('text', '')
                    content_desc = node.get('contentDescription', '')
                    view_id = node.get('viewIdResourceName', '')
                    
                    # Show the most descriptive identifier
                    description = text or content_desc or view_id or 'No description'
                    print(f"{i+1}. {description} ({node.get('className')})")
                    if view_id:
                        print(f"    View ID: {view_id}")
                
                choice = input(f"Enter choice (1-{len(clickable_nodes)}): ").strip()
                try:
                    idx = int(choice) - 1
                    if 0 <= idx < len(clickable_nodes):
                        target_node = clickable_nodes[idx]
                    else:
                        print("Invalid choice")
                        return
                except:
                    print("Invalid input")
                    return
            
            # Step 3: Perform click action
            action_msg = {
                "message": "performAction",
                "hashCode": str(target_node.get('hashCode')),
                "action": "CLICK"
            }
            
            print(f"\nPerforming click on hash code: {target_node.get('hashCode')}")
            await websocket.send(json.dumps(action_msg))
            
            # Wait for the action result
            response_json = await wait_for_response_type(websocket, "actionResult")
            
            print(f"\nAction result:")
            print(f"Success: {response_json.get('success')}")
            print(f"Message: {response_json.get('message')}")
            
            # Optional: Wait a moment and capture the tree to see changes
            await asyncio.sleep(1)
            
            print("\nCapturing tree to see changes...")
            capture_msg = {"message": "capture"}
            await websocket.send(json.dumps(capture_msg))
            response = await websocket.recv()
            print("Tree captured. Check the Inspector App to see the updated UI.")
            
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    print("This script will find elements by text and then click on them")
    asyncio.run(find_and_click_test())