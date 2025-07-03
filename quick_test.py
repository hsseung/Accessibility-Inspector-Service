#!/usr/bin/env python3
"""
Quick test script for basic connectivity
"""

import websocket
import json

def on_message(ws, message):
    try:
        # Handle bytes or string messages
        if isinstance(message, bytes):
            data = message.decode('utf-8')
        else:
            data = message
        
        msg = json.loads(data)
        
        # Simple output
        if msg.get("type") == "accessibilityEvent":
            event_type = msg.get("eventType")
            package = msg.get("packageName", "")[:20]
            print(f"📱 {event_type} from {package}")
            
        elif "children" in msg:
            nodes = sum(1 for _ in str(msg).split('"name"'))
            print(f"🌳 Tree with ~{nodes} nodes")
            
        elif msg.get("message") == "pong":
            print("🏓 Pong!")
            
        else:
            print(f"📩 {msg}")
            
    except Exception as e:
        print(f"❌ Error: {e}")

def main():
    print("🚀 Quick test - connecting to ws://localhost:38301")
    
    ws = websocket.WebSocketApp(
        "ws://localhost:38301",
        on_message=on_message,
        on_open=lambda ws: print("✅ Connected! Interact with Android device..."),
        on_error=lambda ws, error: print(f"❌ Error: {error}"),
    )
    
    try:
        ws.run_forever()
    except KeyboardInterrupt:
        print("\n👋 Bye!")

if __name__ == "__main__":
    main()