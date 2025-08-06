#!/usr/bin/env python3
"""
Test consistency between native findByViewId and custom findByViewId
"""

import asyncio
import websockets
import json

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

async def test_viewid_consistency():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("=== FindByViewId Consistency Test ===")
            print("Comparing native findByViewId vs custom findByViewId")
            
            # Test various viewIds - mix of existing and non-existing
            test_viewids = [
                "com.android.systemui:id/clock",           # System clock
                "com.Slack:id/creation_fab",               # Slack FAB
                "com.Slack:id/nav_search_text_input",      # Slack search
                "com.Slack:id/team_avatar_button",         # Slack avatar
                "android:id/home",                         # Home button
                "android:id/back",                         # Back button
                "nonexistent:id/fake_view",                # Non-existent
            ]
            
            print(f"\nTesting {len(test_viewids)} viewIds...")
            
            differences_found = []
            
            for viewid in test_viewids:
                print(f"\n--- Testing: '{viewid}' ---")
                
                # Native findByViewId
                await websocket.send(json.dumps({
                    "message": "findByViewId",
                    "viewId": viewid
                }))
                native_response = await wait_for_response_type(websocket, "findResult")
                native_count = native_response.get('count', 0)
                native_success = native_response.get('success', False)
                
                # Custom findByViewId
                await websocket.send(json.dumps({
                    "message": "customFindByViewId", 
                    "viewId": viewid
                }))
                custom_response = await wait_for_response_type(websocket, "findResult")
                custom_count = custom_response.get('count', 0)
                custom_success = custom_response.get('success', False)
                
                print(f"  Native: {native_count} nodes (success: {native_success})")
                print(f"  Custom: {custom_count} nodes (success: {custom_success})")
                
                if native_count == custom_count:
                    print(f"  ✓ Consistent results")
                else:
                    print(f"  ⚠️  INCONSISTENT: Native {native_count}, Custom {custom_count}")
                    differences_found.append({
                        'viewId': viewid,
                        'native': native_count,
                        'custom': custom_count,
                        'native_nodes': native_response.get('nodes', []),
                        'custom_nodes': custom_response.get('nodes', [])
                    })
                
                await asyncio.sleep(0.3)
            
            # Analysis
            print(f"\n=== Results Summary ===")
            if not differences_found:
                print("✅ ALL CONSISTENT: Native and custom findByViewId return identical results")
                print("FindByViewId appears to work correctly - no need to deprecate")
            else:
                print(f"❌ INCONSISTENCIES FOUND: {len(differences_found)} viewIds had different results")
                
                for diff in differences_found:
                    print(f"\n🔍 ViewId: {diff['viewId']}")
                    print(f"   Native: {diff['native']} nodes")
                    print(f"   Custom: {diff['custom']} nodes")
                    
                    # Show what each found (first node only)
                    if diff['native_nodes']:
                        node = diff['native_nodes'][0]
                        print(f"   Native found: {node.get('className')} - '{node.get('text')}'")
                    
                    if diff['custom_nodes']:
                        node = diff['custom_nodes'][0]
                        print(f"   Custom found: {node.get('className')} - '{node.get('text')}'")
                
                print(f"\nFindByViewId may also need to be deprecated in favor of custom implementation")
            
            print(f"\n=== Recommendation ===")
            if not differences_found:
                print("✅ Keep using native findByViewId - it's working correctly")
            else:
                print("❌ Consider deprecating native findByViewId in favor of custom implementation")
                print("The same filtering issues that affect findByText may also affect findByViewId")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    print("ViewId Consistency Test")
    print("Make sure you're in the Slack app for best test coverage")
    
    asyncio.run(test_viewid_consistency())