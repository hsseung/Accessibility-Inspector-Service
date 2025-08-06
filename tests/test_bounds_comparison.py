#!/usr/bin/env python3
"""
Compare bounding box values between TreeDebug and findByViewId
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

def find_node_in_tree(tree_data, target_viewid):
    """Recursively find a node with specific viewId in tree data"""
    if isinstance(tree_data, dict):
        # Check if this node has the viewId
        if tree_data.get('resourceId') == target_viewid:
            return tree_data
        
        # Search in children
        if 'children' in tree_data:
            for child in tree_data['children']:
                result = find_node_in_tree(child, target_viewid)
                if result:
                    return result
    elif isinstance(tree_data, list):
        for item in tree_data:
            result = find_node_in_tree(item, target_viewid)
            if result:
                return result
    
    return None

async def compare_bounds():
    uri = "ws://localhost:38301/"
    
    try:
        async with websockets.connect(uri) as websocket:
            print("=== Bounds Comparison Test ===")
            print("Comparing bounding boxes between TreeDebug and findByViewId")
            
            # Test with a known viewId that should exist
            test_viewid = "com.android.systemui:id/clock"
            print(f"\nTesting with viewId: {test_viewid}")
            
            # Get bounds from findByViewId
            print("\n1. Getting bounds from findByViewId...")
            await websocket.send(json.dumps({
                "message": "findByViewId",
                "viewId": test_viewid
            }))
            find_response = await wait_for_response_type(websocket, "findResult")
            
            if not find_response.get('success') or find_response.get('count', 0) == 0:
                print(f"❌ FindByViewId didn't find {test_viewid}")
                print("Try with a different viewId that exists in current app")
                return
            
            find_node = find_response['nodes'][0]
            find_bounds = find_node.get('boundsInScreen', {})
            
            print(f"FindByViewId bounds: {find_bounds}")
            print(f"  left={find_bounds.get('left')}, top={find_bounds.get('top')}")
            print(f"  right={find_bounds.get('right')}, bottom={find_bounds.get('bottom')}")
            
            # Get bounds from tree capture
            print("\n2. Getting bounds from tree capture...")
            await websocket.send(json.dumps({"message": "capture"}))
            tree_response = await websocket.recv()
            
            try:
                tree_data = json.loads(tree_response)
                tree_node = find_node_in_tree(tree_data, test_viewid)
                
                if tree_node:
                    tree_metadata = tree_node.get('metadata', {})
                    tree_bounds = {
                        'left': tree_metadata.get('x1'),
                        'top': tree_metadata.get('y1'),
                        'right': tree_metadata.get('x2'),
                        'bottom': tree_metadata.get('y2')
                    }
                    
                    print(f"TreeDebug bounds: {tree_bounds}")
                    print(f"  x1={tree_bounds.get('left')}, y1={tree_bounds.get('top')}")
                    print(f"  x2={tree_bounds.get('right')}, y2={tree_bounds.get('bottom')}")
                    
                    # Compare the bounds
                    print(f"\n3. Comparison:")
                    bounds_match = (
                        find_bounds.get('left') == tree_bounds.get('left') and
                        find_bounds.get('top') == tree_bounds.get('top') and
                        find_bounds.get('right') == tree_bounds.get('right') and
                        find_bounds.get('bottom') == tree_bounds.get('bottom')
                    )
                    
                    if bounds_match:
                        print("✅ BOUNDS MATCH: Both methods return identical coordinates")
                    else:
                        print("❌ BOUNDS DIFFER:")
                        print(f"  left: find={find_bounds.get('left')} vs tree={tree_bounds.get('left')}")
                        print(f"  top: find={find_bounds.get('top')} vs tree={tree_bounds.get('top')}")
                        print(f"  right: find={find_bounds.get('right')} vs tree={tree_bounds.get('right')}")
                        print(f"  bottom: find={find_bounds.get('bottom')} vs tree={tree_bounds.get('bottom')}")
                        
                        # Calculate differences
                        left_diff = find_bounds.get('left', 0) - tree_bounds.get('left', 0)
                        top_diff = find_bounds.get('top', 0) - tree_bounds.get('top', 0)
                        print(f"  Differences: left={left_diff}, top={top_diff}")
                else:
                    print(f"❌ ViewId {test_viewid} not found in tree capture")
                    print("This could explain the bounds inconsistency - element exists for find but not in tree")
                    
            except json.JSONDecodeError:
                print("❌ Could not parse tree response as JSON")
            
            # Test with another element if available
            print(f"\n=== Testing with Slack element ===")
            slack_viewid = "com.Slack:id/creation_fab"
            
            await websocket.send(json.dumps({
                "message": "findByViewId", 
                "viewId": slack_viewid
            }))
            slack_find = await wait_for_response_type(websocket, "findResult")
            
            if slack_find.get('success') and slack_find.get('count', 0) > 0:
                slack_node = slack_find['nodes'][0]
                slack_find_bounds = slack_node.get('boundsInScreen', {})
                print(f"Slack FAB findByViewId bounds: {slack_find_bounds}")
                
                # Check if it's in tree
                await websocket.send(json.dumps({"message": "capture"}))
                tree_response2 = await websocket.recv()
                tree_data2 = json.loads(tree_response2)
                slack_tree_node = find_node_in_tree(tree_data2, slack_viewid)
                
                if slack_tree_node:
                    slack_tree_metadata = slack_tree_node.get('metadata', {})
                    slack_tree_bounds = {
                        'left': slack_tree_metadata.get('x1'),
                        'top': slack_tree_metadata.get('y1'),
                        'right': slack_tree_metadata.get('x2'),
                        'bottom': slack_tree_metadata.get('y2')
                    }
                    print(f"Slack FAB TreeDebug bounds: {slack_tree_bounds}")
                    
                    slack_match = (
                        slack_find_bounds.get('left') == slack_tree_bounds.get('left') and
                        slack_find_bounds.get('top') == slack_tree_bounds.get('top') and
                        slack_find_bounds.get('right') == slack_tree_bounds.get('right') and
                        slack_find_bounds.get('bottom') == slack_tree_bounds.get('bottom')
                    )
                    
                    print(f"Slack bounds match: {'✅' if slack_match else '❌'}")
                else:
                    print("Slack FAB not found in tree")
            else:
                print("Slack FAB not found with findByViewId")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    print("Bounds Comparison Test")
    print("This will compare bounding boxes between TreeDebug and findByViewId")
    
    asyncio.run(compare_bounds())