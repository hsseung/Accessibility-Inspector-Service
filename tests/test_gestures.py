#!/usr/bin/env python3
"""
Test script for performGesture commands
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

async def test_gestures():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            print("\n=== Testing Gesture Commands ===")
            
            # Safe coordinates for testing (center of typical screen)
            center_x, center_y = 500, 1000
            
            # Test various gesture types
            gestures_to_test = [
                {
                    "gestureType": "TAP",
                    "x": center_x,
                    "y": center_y,
                    "description": "Simple tap gesture"
                },
                {
                    "gestureType": "CLICK", 
                    "x": center_x,
                    "y": center_y,
                    "description": "Click gesture (alias for tap)"
                },
                {
                    "gestureType": "LONG_PRESS",
                    "x": center_x,
                    "y": center_y,
                    "duration": 1500,
                    "description": "Long press gesture"
                },
                {
                    "gestureType": "LONG_CLICK",
                    "x": center_x,
                    "y": center_y,
                    "description": "Long click gesture (default duration)"
                },
                {
                    "gestureType": "SWIPE",
                    "x": center_x,
                    "y": center_y + 200,
                    "endX": center_x,
                    "endY": center_y - 200,
                    "duration": 300,
                    "description": "Swipe up gesture"
                },
                {
                    "gestureType": "SCROLL",
                    "x": center_x,
                    "y": center_y,
                    "endX": center_x - 300,
                    "endY": center_y,
                    "description": "Scroll left gesture"
                },
                {
                    "gestureType": "SCROLL_UP",
                    "x": center_x,
                    "y": center_y,
                    "description": "Scroll up gesture (predefined)"
                },
                {
                    "gestureType": "SCROLL_DOWN",
                    "x": center_x,
                    "y": center_y,
                    "description": "Scroll down gesture (predefined)"
                },
                {
                    "gestureType": "SCROLL_LEFT",
                    "x": center_x,
                    "y": center_y,
                    "description": "Scroll left gesture (predefined)"
                },
                {
                    "gestureType": "SCROLL_RIGHT",
                    "x": center_x,
                    "y": center_y,
                    "description": "Scroll right gesture (predefined)"
                },
                {
                    "gestureType": "DOUBLE_TAP",
                    "x": center_x,
                    "y": center_y,
                    "description": "Double tap gesture"
                }
            ]
            
            for i, gesture in enumerate(gestures_to_test):
                print(f"\n{i+1}. Testing {gesture['description']}...")
                
                # Build gesture message
                gesture_msg = {
                    "message": "performGesture",
                    "gestureType": gesture["gestureType"],
                    "x": gesture["x"],
                    "y": gesture["y"]
                }
                
                # Add optional parameters
                if "endX" in gesture:
                    gesture_msg["endX"] = gesture["endX"]
                if "endY" in gesture:
                    gesture_msg["endY"] = gesture["endY"]
                if "duration" in gesture:
                    gesture_msg["duration"] = gesture["duration"]
                
                print(f"Sending: {json.dumps(gesture_msg)}")
                await websocket.send(json.dumps(gesture_msg))
                
                try:
                    response_json = await wait_for_response_type(websocket, "gestureResult", timeout=10)
                    success = response_json.get('success')
                    message = response_json.get('message')
                    print(f"Result: {'✓' if success else '✗'} {message}")
                    
                    if success:
                        # Wait a moment between gestures to avoid conflicts
                        await asyncio.sleep(1)
                    
                except asyncio.TimeoutError:
                    print("✗ Timeout waiting for gesture result")
            
            # Test error cases
            print(f"\n--- Testing Error Cases ---")
            
            # Missing gestureType
            print(f"\n{len(gestures_to_test)+1}. Testing missing gestureType...")
            error_msg = {
                "message": "performGesture",
                "x": center_x,
                "y": center_y
                # Missing gestureType
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "gestureResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # Invalid coordinates
            print(f"\n{len(gestures_to_test)+2}. Testing invalid coordinates...")
            error_msg = {
                "message": "performGesture",
                "gestureType": "TAP",
                "x": -100,
                "y": -100
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "gestureResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # Unknown gesture type
            print(f"\n{len(gestures_to_test)+3}. Testing unknown gesture type...")
            error_msg = {
                "message": "performGesture",
                "gestureType": "UNKNOWN_GESTURE",
                "x": center_x,
                "y": center_y
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "gestureResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # SWIPE without end coordinates
            print(f"\n{len(gestures_to_test)+4}. Testing SWIPE without end coordinates...")
            error_msg = {
                "message": "performGesture",
                "gestureType": "SWIPE",
                "x": center_x,
                "y": center_y
                # Missing endX, endY
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "gestureResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            print("\n=== Gesture Tests Completed ===")
            print("Note: Some gestures might affect the UI. Check the device screen.")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    print("Testing performGesture commands...")
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    print("WARNING: This will perform actual gestures on the device!")
    
    response = input("Continue? (y/N): ").strip().lower()
    if response == 'y':
        asyncio.run(test_gestures())
    else:
        print("Test cancelled.")