#!/usr/bin/env python3
"""
Test script for launchActivity commands
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

async def test_launch_commands():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            print("\n=== Testing Launch Commands ===")
            
            # Test various launch types
            launch_tests = [
                {
                    "launchType": "PACKAGE",
                    "packageName": "com.android.settings",
                    "description": "Launch Settings app by package name"
                },
                {
                    "launchType": "PACKAGE",
                    "packageName": "com.android.calculator2",
                    "description": "Launch Calculator app by package name"
                },
                {
                    "launchType": "ACTIVITY",
                    "packageName": "com.android.settings",
                    "className": "com.android.settings.Settings",
                    "description": "Launch Settings activity by class name"
                },
                {
                    "launchType": "INTENT",
                    "intentAction": "android.intent.action.VIEW",
                    "data": "https://www.google.com",
                    "description": "Launch browser with URL via intent"
                },
                {
                    "launchType": "INTENT",
                    "intentAction": "android.intent.action.CALL",
                    "data": "tel:123456789",
                    "description": "Launch phone dialer with number"
                },
                {
                    "launchType": "INTENT",
                    "intentAction": "android.intent.action.MAIN",
                    "category": "android.intent.category.HOME",
                    "description": "Launch home screen"
                },
                {
                    "launchType": "INTENT",
                    "intentAction": "android.intent.action.VIEW",
                    "data": "market://details?id=com.android.chrome",
                    "description": "Launch Play Store for Chrome"
                },
                {
                    "launchType": "INTENT",
                    "intentAction": "android.settings.WIFI_SETTINGS",
                    "description": "Launch WiFi settings"
                },
                {
                    "launchType": "INTENT", 
                    "intentAction": "android.intent.action.SEND",
                    "extras": '{"android.intent.extra.TEXT": "Hello World", "android.intent.extra.SUBJECT": "Test"}',
                    "description": "Launch sharing intent with text"
                }
            ]
            
            for i, test in enumerate(launch_tests):
                print(f"\n{i+1}. Testing {test['description']}...")
                
                # Build launch message
                launch_msg = {
                    "message": "launchActivity",
                    "launchType": test["launchType"]
                }
                
                # Add optional parameters
                if "packageName" in test:
                    launch_msg["packageName"] = test["packageName"]
                if "className" in test:
                    launch_msg["className"] = test["className"]
                if "intentAction" in test:
                    launch_msg["intentAction"] = test["intentAction"]
                if "data" in test:
                    launch_msg["data"] = test["data"]
                if "category" in test:
                    launch_msg["category"] = test["category"]
                if "extras" in test:
                    launch_msg["extras"] = test["extras"]
                
                print(f"Sending: {json.dumps(launch_msg)}")
                await websocket.send(json.dumps(launch_msg))
                
                try:
                    response_json = await wait_for_response_type(websocket, "launchResult", timeout=10)
                    success = response_json.get('success')
                    message = response_json.get('message')
                    print(f"Result: {'✓' if success else '✗'} {message}")
                    
                    if success:
                        # Wait a moment for the app to launch
                        await asyncio.sleep(2)
                        print("  App should have launched. Check the device screen.")
                    
                except asyncio.TimeoutError:
                    print("✗ Timeout waiting for launch result")
                
                # Wait between tests to avoid conflicts
                await asyncio.sleep(1)
            
            # Test error cases
            print(f"\n--- Testing Error Cases ---")
            
            # Missing launchType
            print(f"\n{len(launch_tests)+1}. Testing missing launchType...")
            error_msg = {
                "message": "launchActivity",
                "packageName": "com.android.settings"
                # Missing launchType
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "launchResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # Invalid package name
            print(f"\n{len(launch_tests)+2}. Testing invalid package name...")
            error_msg = {
                "message": "launchActivity",
                "launchType": "PACKAGE",
                "packageName": "com.invalid.package.name"
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "launchResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # ACTIVITY type without className
            print(f"\n{len(launch_tests)+3}. Testing ACTIVITY type without className...")
            error_msg = {
                "message": "launchActivity",
                "launchType": "ACTIVITY",
                "packageName": "com.android.settings"
                # Missing className for ACTIVITY type
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "launchResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # INTENT type without intentAction
            print(f"\n{len(launch_tests)+4}. Testing INTENT type without intentAction...")
            error_msg = {
                "message": "launchActivity",
                "launchType": "INTENT",
                "data": "https://www.google.com"
                # Missing intentAction for INTENT type
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "launchResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            # Invalid JSON in extras
            print(f"\n{len(launch_tests)+5}. Testing invalid JSON in extras...")
            error_msg = {
                "message": "launchActivity",
                "launchType": "INTENT",
                "intentAction": "android.intent.action.SEND",
                "extras": "invalid json string"
            }
            await websocket.send(json.dumps(error_msg))
            
            try:
                response_json = await wait_for_response_type(websocket, "launchResult", timeout=5)
                print(f"Result: {'✓' if response_json.get('success') else '✗'} {response_json.get('message')}")
            except asyncio.TimeoutError:
                print("✗ Timeout waiting for error response")
            
            print("\n=== Launch Tests Completed ===")
            print("Note: Some launches might fail if the target app is not installed.")
            print("Successful launches should have opened the corresponding apps on the device.")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    print("Testing launchActivity commands...")
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    print("WARNING: This will launch actual apps on the device!")
    
    response = input("Continue? (y/N): ").strip().lower()
    if response == 'y':
        asyncio.run(test_launch_commands())
    else:
        print("Test cancelled.")