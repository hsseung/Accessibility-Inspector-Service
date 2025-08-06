#!/usr/bin/env python3

import asyncio
import websockets
import json

async def test_findByRegex():
    uri = "ws://localhost:38301"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("Connected to WebSocket server")
            
            # Test cases with different regex patterns
            test_cases = [
                {
                    "name": "Case insensitive 'activity'",
                    "pattern": "(?i)activity"
                },
                {
                    "name": "Contains numbers",
                    "pattern": ".*[0-9]+.*"
                },
                {
                    "name": "radio silence pattern",
                    "pattern": ".*radio silence.*"
                },
                {
                    "name": "Starts with capital letter",
                    "pattern": "^[A-Z].*"
                },
                {
                    "name": "Button or btn (case insensitive)",
                    "pattern": "(?i)(button|btn)"
                },
                {
                    "name": "Exact word 'OK' (case sensitive)",
                    "pattern": "\\bOK\\b"
                },
                {
                    "name": "Time format (HH:MM)",
                    "pattern": "\\d{1,2}:\\d{2}"
                }
            ]
            
            for test_case in test_cases:
                print(f"\n{'='*60}")
                print(f"Testing: {test_case['name']}")
                print(f"Pattern: {test_case['pattern']}")
                print('='*60)
                
                # Send findByRegex command
                command = {
                    "message": "findByRegex",
                    "pattern": test_case["pattern"]
                }
                
                await websocket.send(json.dumps(command))
                response = await websocket.recv()
                
                try:
                    result = json.loads(response)
                    
                    if result.get("type") == "findResult":
                        print(f"✅ Success: {result.get('success', False)}")
                        print(f"📊 Count: {result.get('count', 0)} nodes found")
                        
                        if result.get("nodes"):
                            for i, node in enumerate(result["nodes"]):
                                print(f"\nNode {i+1}:")
                                print(f"  🔤 Text: '{node.get('text', '')}'")
                                print(f"  📝 Content Description: '{node.get('contentDescription', '')}'")
                                print(f"  🏷️  Class: {node.get('className', '')}")
                                print(f"  🆔 ViewId: {node.get('viewIdResourceName', '')}")
                                print(f"  #️⃣  Hash: {node.get('hashCode', '')}")
                                print(f"  👆 Clickable: {node.get('isClickable', False)}")
                                
                                bounds = node.get('boundsInScreen', {})
                                if bounds:
                                    print(f"  📐 Bounds: ({bounds.get('left', 0)}, {bounds.get('top', 0)}) - ({bounds.get('right', 0)}, {bounds.get('bottom', 0)})")
                        else:
                            print("No nodes found matching the pattern")
                    else:
                        print(f"❌ Unexpected response type: {result.get('type', 'unknown')}")
                        print(f"Response: {response[:500]}...")
                        
                except json.JSONDecodeError:
                    print(f"❌ Failed to parse JSON response")
                    print(f"Raw response: {response[:500]}...")
                
                # Small delay between tests
                await asyncio.sleep(0.5)
            
            print(f"\n{'='*60}")
            print("Testing verbose mode")
            print('='*60)
            
            # Test verbose mode
            verbose_command = {
                "message": "findByRegex",
                "pattern": "(?i)activity",
                "verbose": True
            }
            
            await websocket.send(json.dumps(verbose_command))
            response = await websocket.recv()
            
            try:
                result = json.loads(response)
                
                if result.get("type") == "findResult" and result.get("nodes"):
                    print(f"✅ Verbose mode - found {result.get('count', 0)} nodes")
                    
                    # Show first node with verbose details
                    if result["nodes"]:
                        node = result["nodes"][0]
                        print(f"\nVerbose details for first node:")
                        print(f"  🔤 Text: '{node.get('text', '')}'")
                        print(f"  📝 Content Description: '{node.get('contentDescription', '')}'")
                        print(f"  🏷️  Class: {node.get('className', '')}")
                        print(f"  🆔 ViewId: {node.get('viewIdResourceName', '')}")
                        print(f"  #️⃣  Hash: {node.get('hashCode', '')}")
                        print(f"  👆 Clickable: {node.get('isClickable', False)}")
                        print(f"  🔍 Focusable: {node.get('isFocusable', False)}")
                        print(f"  ✅ Enabled: {node.get('isEnabled', False)}")
                        print(f"  📜 Scrollable: {node.get('isScrollable', False)}")
                        print(f"  👁️  Visible: {node.get('isVisibleToUser', False)}")
                        print(f"  💡 Hint: '{node.get('hintText', '')}'")
                        print(f"  🏷️  Tooltip: '{node.get('tooltipText', '')}'")
                        print(f"  🚪 Window ID: {node.get('windowId', '')}")
                        print(f"  👶 Child count: {node.get('childCount', 0)}")
                        
                        actions = node.get('actionList', [])
                        if actions:
                            print(f"  ⚡ Actions: {len(actions)} available")
                            for action in actions[:3]:  # Show first 3 actions
                                print(f"    - ID: {action.get('id', '')}, Label: {action.get('label', 'null')}")
                else:
                    print("❌ No nodes found for verbose test")
                    
            except json.JSONDecodeError:
                print(f"❌ Failed to parse verbose response")
                print(f"Raw response: {response[:500]}...")
            
            print(f"\n{'='*60}")
            print("Testing error cases")
            print('='*60)
            
            # Test error cases
            error_tests = [
                {
                    "name": "Missing pattern parameter",
                    "command": {"message": "findByRegex"}
                },
                {
                    "name": "Invalid regex pattern",
                    "command": {"message": "findByRegex", "pattern": "[unclosed"}
                }
            ]
            
            for error_test in error_tests:
                print(f"\n🧪 Testing: {error_test['name']}")
                
                await websocket.send(json.dumps(error_test["command"]))
                response = await websocket.recv()
                
                try:
                    result = json.loads(response)
                    
                    if result.get("type") == "findResult":
                        success = result.get("success", True)
                        message = result.get("message", "")
                        
                        if not success:
                            print(f"✅ Correctly handled error: {message}")
                        else:
                            print(f"❌ Expected error but got success")
                    else:
                        print(f"❌ Unexpected response type: {result.get('type', 'unknown')}")
                        
                except json.JSONDecodeError:
                    print(f"❌ Failed to parse error response")
                    print(f"Raw response: {response[:200]}...")
    
    except websockets.exceptions.ConnectionRefused:
        print("❌ Connection refused. Make sure:")
        print("  1. The Android app is running")
        print("  2. The accessibility service is enabled")
        print("  3. Port forwarding is set up: adb forward tcp:38301 tcp:38301")
    except Exception as e:
        print(f"❌ Error: {e}")

if __name__ == "__main__":
    print("🔍 Testing findByRegex functionality")
    print("📱 Make sure your Android device is connected and the service is running")
    print("🔧 Run: adb forward tcp:38301 tcp:38301")
    print()
    
    asyncio.run(test_findByRegex())
