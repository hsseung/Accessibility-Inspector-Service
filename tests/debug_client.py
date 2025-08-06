#!/usr/bin/env python3
"""
Debug client to see raw messages
"""

import websocket
import json
import time

# Track UI change and tree capture timing
last_ui_change_time = None
capture_start_time = None

def on_message(ws, message):
    global last_ui_change_time, capture_start_time
    
    # Record message receive time
    receive_time = time.time()
    
    # First, decode the message
    try:
        if isinstance(message, bytes):
            data = message.decode('utf-8')
        else:
            data = message
        
        # Parse JSON to determine message type for header
        try:
            msg = json.loads(data)
            
            # Track timing for performance analysis
            timing_info = ""
            
            # Determine message type for header and calculate timing
            if msg.get("type") == "accessibilityEvent":
                event_type = msg.get('eventType')
                header = f"🎯 ACCESSIBILITY EVENT: {event_type}"
                
                # Track UI changes that might trigger tree capture
                # WINDOW_CONTENT_CHANGED is the primary stability timer reset event
                ui_change_events = ["WINDOW_CONTENT_CHANGED", "WINDOW_STATE_CHANGED", "VIEW_CLICKED", "VIEW_FOCUSED"]
                if event_type in ui_change_events:
                    last_ui_change_time = receive_time
                    if event_type == "WINDOW_CONTENT_CHANGED":
                        timing_info = f" [🔄 STABILITY TIMER RESET]"
                    else:
                        timing_info = f" [UI change detected - {event_type}]"
                    
            elif msg.get("type") == "stableTree":
                header = f"🌳 STABLE TREE (ts: {msg.get('timestamp')})"
                
                # Calculate time since last UI change
                if last_ui_change_time:
                    total_time = receive_time - last_ui_change_time
                    timing_info = f" [⏱️ {total_time:.1f}s since UI change]"
                else:
                    timing_info = " [⏱️ No UI change tracked]"
                    
            elif msg.get("type") == "treeBeforeEvent":
                header = f"🌳 TREE BEFORE: {msg.get('eventType')} [DEPRECATED]"
            elif msg.get("type") == "tree" or "children" in msg:
                header = "🌳 TREE MESSAGE"
                
                # This is likely a manual capture response
                if capture_start_time:
                    capture_time = receive_time - capture_start_time
                    timing_info = f" [⏱️ {capture_time:.1f}s capture time]"
                    capture_start_time = None  # Reset
                    
            elif msg.get("type") in ["actionResult", "gestureResult", "launchResult", "findResult"]:
                header = f"📋 RESULT: {msg.get('type')}"
            elif msg.get("message") == "pong":
                header = "🏓 PONG"
            else:
                header = f"❓ OTHER: {msg.get('type', 'NO TYPE')}"
                
            # Add timing info to header
            header += timing_info
                
        except json.JSONDecodeError:
            header = "❌ INVALID JSON"
            
    except Exception:
        header = "❌ DECODE ERROR"
        data = str(message)[:1000]
    
    print(f"\n=== {header} ===")
    
    # Handle different message types
    try:
        if msg.get("type") == "stableTree":
            children_count = len(msg.get('children', []))
            print(f"Children count: {children_count}")
            if children_count > 0:
                print(process_tree_for_streaming(msg['children'][0]))
        elif msg.get("type") == "treeBeforeEvent":
            print("[DEPRECATED] This message type should no longer be sent")
            print(process_tree_for_streaming(msg['children'][0]))
        elif msg.get("type") == "accessibilityEvent":
            print(f"Event: {msg.get('eventType')} (ID: {msg.get('eventTypeId', 'N/A')})")
            print(f"Package: {msg.get('packageName', 'N/A')}")
            print(f"Class: {msg.get('className', 'N/A')}")
            print(f"Timestamp: {msg.get('timestamp', 'N/A')}")
            
            # Event text content
            if 'text' in msg:
                text_content = msg['text']
                if isinstance(text_content, list):
                    print(f"Text: {text_content}")
                else:
                    print(f"Text: '{text_content}'")
            
            # Source node information
            if 'source' in msg:
                source = msg['source']
                print(f"Source Node:")
                print(f"  Name: {source.get('name', 'N/A')}")
                print(f"  Text: '{source.get('text', '')}'")
                print(f"  Content Description: '{source.get('contentDescription', '')}'")
                print(f"  Resource ID: {source.get('resourceId', 'N/A')}")
                if 'metadata' in source:
                    metadata = source['metadata']
                    print(f"  Hash Code: {metadata.get('hashCode', 'N/A')}")
                    print(f"  Role: {metadata.get('role', 'N/A')}")
            
            # Scroll-specific information
            if msg.get('eventType') == 'SCROLL_SEQUENCE_END':
                print(f"Scroll Data:")
                print(f"  Total X: {msg.get('totalScrollX', 'N/A')}")
                print(f"  Total Y: {msg.get('totalScrollY', 'N/A')}")
                if 'scrollTimestamps' in msg:
                    timestamps = msg['scrollTimestamps']
                    print(f"  Event count: {len(timestamps)}")
                    if len(timestamps) > 1:
                        duration = timestamps[-1] - timestamps[0]
                        print(f"  Duration: {duration}ms")
            
            # Text input specific information
            if msg.get('eventType') == 'TEXT_SEQUENCE_END':
                print(f"Text Input Data:")
                print(f"  Session text: '{msg.get('sessionText', '')}'")
                print(f"  Event count: {msg.get('textEventCount', 'N/A')}")
                print(f"  Paste events: {msg.get('pasteEventCount', 'N/A')}")
                if 'textFieldSource' in msg:
                    field = msg['textFieldSource']
                    print(f"  Field: {field.get('name', 'N/A')} - '{field.get('text', '')}'")
        else:
            print(f"Length: {len(data)}")
            print(f"JSON preview: {data[:1000]}{'...' if len(data) > 1000 else ''}")
    except Exception as e:
        print(f"Error processing message: {e}")
        print(f"Length: {len(data)}")
        print(f"JSON preview: {data[:1000]}{'...' if len(data) > 1000 else ''}")
    
    print("==================\n")

def on_open(ws):
    global capture_start_time
    print("✅ Connected! Now click something on Android...")
    print("💡 Press Enter and type commands:")
    print("   'c' + Enter = capture tree")
    print("   'p' + Enter = ping") 
    print("   'q' + Enter = quit")
    
    # Test immediate capture
    print("🧪 Testing manual capture in 2 seconds...")
    def delayed_capture():
        time.sleep(2)
        print("📤 Sending test capture command...")
        capture_start_time = time.time()
        ws.send('{"message":"capture"}')
    
    import threading
    test_thread = threading.Thread(target=delayed_capture, daemon=True)
    test_thread.start()
    
    # Input handler
    def input_handler():
        global capture_start_time
        while True:
            try:
                print("\n> ", end="", flush=True)
                cmd = input().strip().lower()
                if cmd == 'c':
                    print("📤 Sending capture command...")
                    capture_start_time = time.time()
                    ws.send('{"message":"capture"}')
                elif cmd == 'p':
                    print("📤 Sending ping...")
                    ws.send('{"message":"ping"}')
                elif cmd == 'q':
                    print("👋 Closing connection...")
                    ws.close()
                    break
                else:
                    print(f"Unknown command: {cmd}")
            except (EOFError, KeyboardInterrupt):
                break
    
    input_thread = threading.Thread(target=input_handler, daemon=True)
    input_thread.start()

def main():
    print("🔍 Debug client starting...")
    
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
        print("\n👋 Exiting...")

def process_tree_for_streaming(tree_data):
    """Process tree data for streaming memory"""
    try:
        from utils_a11y import (
            remove_nodes_with_properties,
            android_tree_to_html_with_hashcode,
            remove_empty_divs,
            remove_redundant_divs
        )

        # Process tree data
        processed_tree = remove_nodes_with_properties(tree_data, {"visibility": "invisible"})
        html = android_tree_to_html_with_hashcode(processed_tree, clickable=True)
        html = remove_empty_divs(html)
        html = remove_redundant_divs(html)
        return html
    except ImportError:
        return f"Tree processing unavailable (utils_a11y not found). Raw tree: {str(tree_data)[:200]}..."
    except Exception as e:
        return f"Tree processing error: {e}. Raw tree: {str(tree_data)[:200]}..."

if __name__ == "__main__":
    main()
