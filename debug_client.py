#!/usr/bin/env python3
"""
Debug client to see raw messages
"""

import websocket
import json

def on_message(ws, message):
    # First, decode the message
    try:
        if isinstance(message, bytes):
            data = message.decode('utf-8')
        else:
            data = message
        
        # Parse JSON to determine message type for header
        try:
            msg = json.loads(data)
            
            # Determine message type for header
            if msg.get("type") == "accessibilityEvent":
                header = f"🎯 ACCESSIBILITY EVENT: {msg.get('eventType')}"
            elif msg.get("type") == "treeBeforeEvent":
                header = f"🌳 TREE BEFORE: {msg.get('eventType')}"
            elif "children" in msg:
                header = "🌳 TREE MESSAGE"
            elif msg.get("type") in ["actionResult", "gestureResult", "launchResult"]:
                header = f"📋 RESULT: {msg.get('type')}"
            elif msg.get("message") == "pong":
                header = "🏓 PONG"
            else:
                header = f"❓ OTHER: {msg.get('type', 'NO TYPE')}"
                
        except json.JSONDecodeError:
            header = "❌ INVALID JSON"
            
    except Exception:
        header = "❌ DECODE ERROR"
        data = str(message)[:1000]
    
    print(f"\n=== {header} ===")
    print(f"Length: {len(data)}")
    print(f"JSON preview: {data[:1000]}{'...' if len(data) > 1000 else ''}")
    print("==================\n")

def main():
    print("🔍 Debug client starting...")
    
    ws = websocket.WebSocketApp(
        "ws://localhost:38301",
        on_message=on_message,
        on_open=lambda ws: print("✅ Connected! Now click something on Android..."),
        on_error=lambda ws, error: print(f"❌ Error: {error}"),
        on_close=lambda ws, a, b: print("🔌 Disconnected")
    )
    
    try:
        ws.run_forever()
    except KeyboardInterrupt:
        print("\n👋 Exiting...")

if __name__ == "__main__":
    main()