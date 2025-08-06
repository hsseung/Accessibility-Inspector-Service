#!/usr/bin/env python3
"""
Test script for visibleOnly parameter in tree capture commands
"""

import websocket
import json
import time

def on_message(ws, message):
    try:
        msg = json.loads(message)
        msg_type = msg.get("type", "unknown")
        
        if msg_type == "tree":
            children = msg.get("children", [])
            tree_size = len(json.dumps(msg))
            node_count = count_nodes(children)
            
            print(f"📊 Tree received:")
            print(f"  Total size: {tree_size:,} bytes")
            print(f"  Node count: {node_count}")
            print(f"  JSON structure: {len(children)} top-level windows")
            
            # Show first few nodes for verification
            if children and len(children) > 0:
                first_window = children[0]
                if "children" in first_window and len(first_window["children"]) > 0:
                    first_nodes = first_window["children"][:3]  # First 3 nodes
                    print(f"  Sample nodes:")
                    for i, node in enumerate(first_nodes):
                        metadata = node.get("metadata", {})
                        name = node.get("name", "?")
                        visibility = metadata.get("visibility", "visible")
                        text = metadata.get("text", "")
                        print(f"    {i+1}. {name} ({visibility}) - '{text[:30]}{'...' if len(text) > 30 else ''}'")
        else:
            print(f"🔄 Received: {msg_type}")
            
    except json.JSONDecodeError:
        print(f"❌ Invalid JSON: {message[:100]}...")
    except Exception as e:
        print(f"❌ Error processing message: {e}")

def count_nodes(children):
    """Recursively count all nodes in the tree"""
    count = len(children)
    for child in children:
        if "children" in child:
            count += count_nodes(child["children"])
    return count

def on_open(ws):
    print("✅ Connected to Accessibility Inspector")
    print("\n🧪 Testing visibleOnly parameter...")
    
    def run_tests():
        time.sleep(1)
        
        print("\n1️⃣ Testing capture without visibleOnly (full tree):")
        ws.send('{"message":"capture"}')
        
        time.sleep(3)
        
        print("\n2️⃣ Testing capture with visibleOnly=false (full tree):")
        ws.send('{"message":"capture", "visibleOnly":false}')
        
        time.sleep(3)
        
        print("\n3️⃣ Testing capture with visibleOnly=true (filtered tree):")
        ws.send('{"message":"capture", "visibleOnly":true}')
        
        time.sleep(3)
        
        print("\n4️⃣ Testing captureNotImportant with visibleOnly=true:")
        ws.send('{"message":"captureNotImportant", "visibleOnly":true}')
        
        time.sleep(3)
        print("\n✅ Test sequence complete!")
    
    import threading
    test_thread = threading.Thread(target=run_tests, daemon=True)
    test_thread.start()

def main():
    print("🔍 Testing visibleOnly parameter for tree capture...")
    print("📱 Make sure a complex app (like NYT, Slack) is open on your device")
    
    ws = websocket.WebSocketApp(
        "ws://localhost:38301",
        on_message=on_message,
        on_open=on_open,
        on_error=lambda ws, error: print(f"❌ Error: {error}"),
        on_close=lambda ws, a, b: print("🔌 Disconnected")
    )
    
    try:
        ws.run_forever()
    except KeyboardInterrupt:
        print("\n👋 Test interrupted by user")

if __name__ == "__main__":
    main()