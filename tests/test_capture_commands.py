#!/usr/bin/env python3
"""
Test script to verify all capture commands work properly
"""

import asyncio
import websockets
import json
import sys

def count_nodes_recursive(node):
    """Recursively count all nodes in the tree"""
    count = 1  # Count current node
    
    if isinstance(node, dict) and 'children' in node:
        for child in node['children']:
            count += count_nodes_recursive(child)
    
    return count

async def test_capture_commands():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            
            # Test all capture commands
            commands = [
                {"message": "capture", "description": "Capture important nodes only"},
                {"message": "captureNotImportant", "description": "Capture all nodes including non-important"}
            ]
            
            for i, cmd in enumerate(commands):
                print(f"\n=== Test {i+1}: {cmd['description']} ===")
                print(f"Sending: {json.dumps(cmd)}")
                
                await websocket.send(json.dumps(cmd))
                
                try:
                    # Set timeout for response
                    response = await asyncio.wait_for(websocket.recv(), timeout=15.0)
                    
                    # Check response type and size
                    if isinstance(response, bytes):
                        print("✓ Received binary response (likely GZIP compressed tree)")
                        print(f"  Size: {len(response)} bytes")
                    elif response.startswith('{"type":"tree"'):
                        print("✓ Received JSON tree response")
                        print(f"  JSON size: {len(response)} characters")
                        tree_json = json.loads(response)
                        if 'children' in tree_json:
                            print(f"  Top-level nodes: {len(tree_json['children'])}")
                            # Count total nodes recursively
                            total_nodes = count_nodes_recursive(tree_json)
                            print(f"  Total nodes in tree: {total_nodes}")
                    else:
                        print("✓ Received text response")
                        print(f"  Size: {len(response)} characters")
                        print(f"  Preview: {response[:100]}...")
                        
                except asyncio.TimeoutError:
                    print("✗ Timeout - no response within 15 seconds")
                except json.JSONDecodeError:
                    print("✗ Could not parse response as JSON")
                except Exception as e:
                    print(f"✗ Error: {e}")
            
            # Test ping to make sure connection is still good
            print(f"\n=== Connection Test ===")
            await websocket.send(json.dumps({"message": "ping"}))
            
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                response_json = json.loads(response)
                if response_json.get("message") == "pong":
                    print("✓ Ping/pong successful - connection is healthy")
                else:
                    print(f"✗ Unexpected ping response: {response}")
            except Exception as e:
                print(f"✗ Ping failed: {e}")
                
    except Exception as e:
        print(f"Connection error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    print("Testing all capture commands...")
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    asyncio.run(test_capture_commands())