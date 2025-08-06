#!/usr/bin/env python3
"""
Test to compare native Android find methods vs custom recursive implementations
"""

import asyncio
import websockets
import json
import time

async def wait_for_response_type(websocket, expected_type, timeout=10):
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

async def compare_find_methods():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("=== Native vs Custom Find Comparison ===")
            print("This test compares Android's native find methods with custom recursive implementations\n")
            
            # Test cases for text search
            text_tests = [
                {"text": ":", "desc": "Colon (common in time)"},
                {"text": "Slack", "desc": "App name"},
                {"text": "Settings", "desc": "Settings text"},
                {"text": "11", "desc": "Numbers (in time)"},
                {"text": "a", "desc": "Single letter"},
            ]
            
            print("=== Text Search Comparison ===")
            for test in text_tests:
                print(f"\nSearching for: '{test['text']}' ({test['desc']})")
                
                # Native findByText
                start_time = time.time()
                await websocket.send(json.dumps({
                    "message": "findByText",
                    "text": test['text']
                }))
                native_response = await wait_for_response_type(websocket, "findResult")
                native_time = time.time() - start_time
                native_count = native_response.get('count', 0)
                
                # Custom findByText
                start_time = time.time()
                await websocket.send(json.dumps({
                    "message": "customFindByText",
                    "text": test['text']
                }))
                custom_response = await wait_for_response_type(websocket, "findResult")
                custom_time = time.time() - start_time
                custom_count = custom_response.get('count', 0)
                
                # Compare results
                print(f"  Native method: {native_count} nodes found in {native_time:.3f}s")
                print(f"  Custom method: {custom_count} nodes found in {custom_time:.3f}s")
                
                if 'stats' in custom_response:
                    print(f"  Tree stats: {custom_response['stats']}")
                
                if native_count != custom_count:
                    print(f"  ⚠️  DIFFERENCE: Native found {native_count}, Custom found {custom_count}")
                    
                    # Show what each found
                    if native_count > 0:
                        print("  Native found:")
                        for i, node in enumerate(native_response.get('nodes', [])[:3]):
                            print(f"    {i+1}. {node.get('className')} - text:'{node.get('text')}' desc:'{node.get('contentDescription')}'")
                    
                    if custom_count > 0:
                        print("  Custom found:")
                        for i, node in enumerate(custom_response.get('nodes', [])[:3]):
                            print(f"    {i+1}. {node.get('className')} - text:'{node.get('text')}' desc:'{node.get('contentDescription')}'")
                else:
                    print(f"  ✓ Both methods found same count")
                
                # Small delay between tests
                await asyncio.sleep(0.5)
            
            # Test viewId search
            print("\n\n=== ViewId Search Comparison ===")
            viewid_tests = [
                {"viewId": "com.android.systemui:id/clock", "desc": "System clock"},
                {"viewId": "com.Slack:id/creation_fab", "desc": "Slack FAB"},
                {"viewId": "android:id/home", "desc": "Home button"},
                {"viewId": "android:id/text1", "desc": "Generic text1"},
            ]
            
            for test in viewid_tests:
                print(f"\nSearching for: '{test['viewId']}' ({test['desc']})")
                
                # Native findByViewId
                start_time = time.time()
                await websocket.send(json.dumps({
                    "message": "findByViewId",
                    "viewId": test['viewId']
                }))
                native_response = await wait_for_response_type(websocket, "findResult")
                native_time = time.time() - start_time
                native_count = native_response.get('count', 0)
                
                # Custom findByViewId
                start_time = time.time()
                await websocket.send(json.dumps({
                    "message": "customFindByViewId",
                    "viewId": test['viewId']
                }))
                custom_response = await wait_for_response_type(websocket, "findResult")
                custom_time = time.time() - start_time
                custom_count = custom_response.get('count', 0)
                
                # Compare results
                print(f"  Native method: {native_count} nodes found in {native_time:.3f}s")
                print(f"  Custom method: {custom_count} nodes found in {custom_time:.3f}s")
                
                if native_count != custom_count:
                    print(f"  ⚠️  DIFFERENCE: Native found {native_count}, Custom found {custom_count}")
                else:
                    print(f"  ✓ Both methods found same count")
                
                await asyncio.sleep(0.5)
            
            print("\n=== Summary ===")
            print("If there are differences between native and custom methods, it could indicate:")
            print("1. Android's native methods have internal filtering or optimizations")
            print("2. Some nodes are not accessible through standard traversal")
            print("3. Window/security restrictions on certain nodes")
            print("4. Implementation differences in string matching or tree traversal")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    print("Native vs Custom Find Comparison Test")
    print("Make sure to run 'adb forward tcp:38301 tcp:38301' before running this test")
    print("After building and installing the updated app, run this test\n")
    
    asyncio.run(compare_find_methods())