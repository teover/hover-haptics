# Hover Haptics

My grandma is 84 and has 2,277 total level. She has played since 2004, and her account has been banned twice: once for "suspiciously consistent" willow cutting, and once for constantly sending the Wise Old Man messages in public chat that Jagex's ban appeal described only as "not about questing." She does not plan to stop playing.

Her eyes, however, have stopped cooperating. Last week she spent her entire afternoon trying to milk Brutus. She also told me "every NPC looks like a potato wearing a hat".

So I built her this. Tag the things you care about, and your controller buzzes when your mouse finds them. Grandma now holds a gamepad in her left hand purely as a divining rod, and it shakes when she's found the banker. She calls it "the dowsing stick." Her willow logs per hour are higher than ever, which we expect will get her banned a third time.

Why Intiface Central? When I asked Grandma to install a companion app for the controller, she told me she "already had Intiface." The plugin therefore uses Intiface, and that is the end of this section.

**In short:** Hover Haptics rumbles your gamepad when your mouse moves onto an NPC, object, ground item or inventory item you've tagged, or onto a tile marked with the Ground Markers plugin. This helps you pay even less attention to the game. 

**Requires the free [Intiface Central](https://intiface.com/central/) app**, which does the actual vibrating. See [How it works](#how-it-works).

## Setup

1. Install [Intiface Central](https://intiface.com/central/) and press **Start Server** (the default address is `ws://127.0.0.1:12345`).
2. Connect your controller. XInput (Xbox-compatible) controllers are supported; for PlayStation and other controllers, use Steam Input or DS4Windows to present them as XInput.
3. In Intiface Central, press **Start Scanning** until the controller appears. The plugin also scans for 30 seconds after it connects.

A gamepad infobox shows the connection status:

| Infobox | Meaning |
|---|---|
| Green **OK** | Connected, with a controller ready. Hover the infobox to see which one. |
| Yellow **!** | Connected to Intiface Central, but no controller found. Connect it and press **Start Scanning**. |
| Red **!** | Not connected. Start Intiface Central and press **Start Server**. The plugin retries every 10 seconds. |

A chat message also tells you when the status changes.

## Usage

Shift + right-click an NPC, object, ground item or inventory item, choose **Vibrate on hover**, and pick **Light**, **Medium** or **Strong**. Every NPC, object or item with that ID is now tagged. Your controller pulses once each time the mouse moves onto one. The current intensity is shown in green; choose **Remove** to untag it.

To vibrate on tiles marked with the Ground Markers plugin, set **Marked tiles** in settings to an intensity.

## Settings

- **Intiface URL**: change this if Intiface Central runs on a different port.
- **Status infobox**: show the status infobox always, only when something is wrong, or never.
- **Marked tiles**: the intensity for Ground Markers tiles, or Off.
- **Motor**: both motors, or only the heavy (low frequency) or light (high frequency) one.
- **Require Shift for menu entry**: turn this off to always show the menu entry.
- **Intensities**: the strength and pulse length of Light, Medium and Strong.
- **Tagged**: the tagged IDs as `id:intensity` pairs, which you can edit by hand.

## How it works

RuneLite plugins can't use native code, so they can't access a gamepad directly. Instead, Hover Haptics is a client of [Intiface Central](https://intiface.com/central/), which exposes connected devices over the open [Buttplug protocol](https://buttplug-spec.docs.buttplug.io/), a JSON protocol over WebSocket used for haptic devices, including XInput gamepads. Intiface was determined to be the best fit for this purpose. 

The plugin connects to `ws://127.0.0.1:12345` and uses message version 3:

1. `RequestServerInfo` performs the handshake, then `RequestDeviceList` and `StartScanning` find devices. Scanning stops after 30 seconds.
2. `DeviceAdded` / `DeviceRemoved` keep the device list current. Only devices with `Vibrate` actuators are used. The **Motor** setting maps low frequency to actuator 0 and high frequency to actuator 1.
3. Each pulse is a `ScalarCmd` at the intensity's strength, followed by `StopDeviceCmd` after the intensity's duration.
4. If the server asks for pings (`MaxPingTime`), the plugin sends `Ping` at half that interval.

The connection only goes to the address in **Intiface URL**, which defaults to your own computer. Nothing is sent to the internet.

