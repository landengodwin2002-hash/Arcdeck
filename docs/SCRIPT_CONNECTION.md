# Termux scripts inside ArcDeck

Use PFP's top command bar to paste a script, choose Run, review it and start a managed background run. Its output appears in Terminal. Grant Termux's RUN_COMMAND permission and enable `allow-external-apps=true` in Termux as before. Managed runs have Pause, Resume and Stop controls; Stop terminates their process group. Closing Terminal stops those managed runs. Scripts that create separate sessions/services need their own cleanup.

To control the launcher from a script, open **Automation → Start script connection → Copy Termux connection example**. Paste that exact example into Termux. Its bearer token is private to your installation. Keep the `Authorization` header and localhost address from the copied example when changing its JSON body below. No OpenAI API key or Discord bot token is required for these launcher controls.

| JSON body | Result |
| --- | --- |
| `{"op":"window","action":"open","tool":"CHATGPT"}` | Open the ChatGPT web window |
| `{"op":"window","action":"open","tool":"DISCORD"}` | Open the Discord web window |
| `{"op":"window","action":"open","tool":"MEDIA"}` | Open the Media web window |
| `{"op":"window","action":"tile","layout":"side"}` | Tile active windows side by side |
| `{"op":"window","action":"tile","layout":"grid"}` | Arrange windows in a grid |
| `{"op":"window","action":"minimize","tool":"CHATGPT"}` | Minimize and unload that web window |
| `{"op":"window","action":"close","tool":"TERMINAL"}` | Close Terminal and stop managed scripts |
| `{"op":"window","action":"stop"}` | Suspend windows and stop managed scripts |
| `{"op":"clipboard","text":"Text for another app"}` | Copy text, without reading the clipboard |
| `{"op":"app","package":"com.discord"}` | Request the allowed native app |
| `{"op":"ui","action":"paste","package":"com.discord","text":"Draft text"}` | Replace the focused editable field in the allowed frontmost app |
| `{"op":"ui","action":"tap","package":"com.discord","x":120,"y":220}` | Tap coordinates inside the allowed app's window |
| `{"op":"ui","action":"swipe","package":"com.discord","x":200,"y":500,"toX":200,"toY":200}` | Swipe within the allowed app's window |
| `{"op":"ui","action":"back","package":"com.discord"}` | Request Back while that app is in front |

UI actions require you to enable **ArcDeck optional script controls** in Android Accessibility and explicitly allow the target app in Automation. Coordinates are device pixels; check your actual layout. Password inputs and mismatched frontmost packages are rejected. The text action does not press Send. Native-app launches remain subject to Android's background-launch rules.

Send the example's authorization header to `GET /status` to check whether the connection is active. Window replies report `queued`; UI actions report `action_requested`, which is not a guarantee of a website/app's resulting business action. Window requests are queued in order with a 64-request bound. Bodies are limited to 32 KiB. The endpoint reads no app messages and does not expose screenshots, accessibility trees, passwords or clipboard contents.

The connection binds only to `127.0.0.1:47831`; a foreground notification offers Stop. It stops when the screen turns off and rejects actions while locked. While explicitly active, it permits managed scripts to continue in frontmost allowed native apps; stopping the connection pauses those runs. Revoke connection token invalidates old scripts' credentials. App permissions and the connection token are excluded from organization backups. Use an app's supported API for more complex integrations when available; Android accessibility support differs between apps and web clients.
