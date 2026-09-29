package com.hoverhaptics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Minimal Buttplug protocol (v3) client for Intiface Central, which does the
 * actual gamepad rumbling. See https://buttplug-spec.docs.buttplug.io/
 */
@Slf4j
class IntifaceClient extends WebSocketListener
{
	private static final int RECONNECT_SECONDS = 10;
	private static final int SCAN_SECONDS = 30;

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final String url;
	/** called on an OkHttp thread whenever the connection or device list changes */
	private final Runnable onStatusChanged;

	private final AtomicInteger messageId = new AtomicInteger(1);
	/** device index -> indexes of its Vibrate actuators */
	private final Map<Integer, List<Integer>> devices = new ConcurrentHashMap<>();
	private final Map<Integer, String> deviceNames = new ConcurrentHashMap<>();

	private WebSocket webSocket;
	private volatile boolean ready;
	private boolean closed;
	private ScheduledFuture<?> reconnectFuture;
	private ScheduledFuture<?> pingFuture;
	private ScheduledFuture<?> stopScanFuture;
	private ScheduledFuture<?> stopFuture;

	IntifaceClient(OkHttpClient okHttpClient, Gson gson, ScheduledExecutorService executor, String url, Runnable onStatusChanged)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
		this.url = url;
		this.onStatusChanged = onStatusChanged;
	}

	String getUrl()
	{
		return url;
	}

	synchronized void connect()
	{
		if (closed)
		{
			return;
		}

		Request request;
		try
		{
			request = new Request.Builder().url(url).build();
		}
		catch (IllegalArgumentException e)
		{
			log.warn("Invalid Intiface URL {}", url);
			return;
		}
		webSocket = okHttpClient.newWebSocket(request, this);
	}

	synchronized void close()
	{
		closed = true;
		cancel(reconnectFuture);
		cancel(pingFuture);
		cancel(stopScanFuture);
		cancel(stopFuture);
		if (webSocket != null)
		{
			if (ready)
			{
				send("StopAllDevices", new JsonObject());
			}
			webSocket.close(1000, null);
			webSocket = null;
		}
		ready = false;
		devices.clear();
		deviceNames.clear();
	}

	boolean isConnected()
	{
		return ready;
	}

	int deviceCount()
	{
		return devices.size();
	}

	List<String> deviceNames()
	{
		return new ArrayList<>(deviceNames.values());
	}

	/**
	 * @param strength 0-1
	 * @param motors   which vibrate actuators to use, by position; null for all
	 */
	synchronized void pulse(double strength, int[] motors, int durationMs)
	{
		if (!ready || devices.isEmpty())
		{
			return;
		}

		for (Map.Entry<Integer, List<Integer>> device : devices.entrySet())
		{
			List<Integer> actuators = device.getValue();
			JsonArray scalars = new JsonArray();
			for (int i = 0; i < actuators.size(); i++)
			{
				if (motors != null && !contains(motors, i))
				{
					continue;
				}
				JsonObject scalar = new JsonObject();
				scalar.addProperty("Index", actuators.get(i));
				scalar.addProperty("Scalar", strength);
				scalar.addProperty("ActuatorType", "Vibrate");
				scalars.add(scalar);
			}
			if (scalars.size() == 0)
			{
				continue;
			}

			JsonObject cmd = new JsonObject();
			cmd.addProperty("DeviceIndex", device.getKey());
			cmd.add("Scalars", scalars);
			send("ScalarCmd", cmd);
		}

		cancel(stopFuture);
		stopFuture = executor.schedule(this::stopAll, durationMs, TimeUnit.MILLISECONDS);
	}

	private synchronized void stopAll()
	{
		for (int deviceIndex : devices.keySet())
		{
			JsonObject cmd = new JsonObject();
			cmd.addProperty("DeviceIndex", deviceIndex);
			send("StopDeviceCmd", cmd);
		}
	}

	@Override
	public void onOpen(WebSocket webSocket, Response response)
	{
		JsonObject info = new JsonObject();
		info.addProperty("ClientName", "RuneLite Hover Haptics");
		info.addProperty("MessageVersion", 3);
		sendOn(webSocket, "RequestServerInfo", info);
	}

	@Override
	public synchronized void onMessage(WebSocket webSocket, String text)
	{
		if (webSocket != this.webSocket)
		{
			return;
		}

		JsonArray messages;
		try
		{
			messages = gson.fromJson(text, JsonArray.class);
		}
		catch (RuntimeException e)
		{
			log.debug("Unparseable Intiface message {}", text, e);
			return;
		}

		for (JsonElement element : messages)
		{
			for (Map.Entry<String, JsonElement> message : element.getAsJsonObject().entrySet())
			{
				handle(message.getKey(), message.getValue().getAsJsonObject());
			}
		}
	}

	private void handle(String type, JsonObject body)
	{
		switch (type)
		{
			case "ServerInfo":
				ready = true;
				log.info("Connected to Intiface server {}", body.has("ServerName") ? body.get("ServerName").getAsString() : "");
				int maxPing = body.has("MaxPingTime") ? body.get("MaxPingTime").getAsInt() : 0;
				if (maxPing > 0)
				{
					int interval = Math.max(maxPing / 2, 100);
					pingFuture = executor.scheduleAtFixedRate(() -> send("Ping", new JsonObject()), interval, interval, TimeUnit.MILLISECONDS);
				}
				send("RequestDeviceList", new JsonObject());
				send("StartScanning", new JsonObject());
				stopScanFuture = executor.schedule(() -> send("StopScanning", new JsonObject()), SCAN_SECONDS, TimeUnit.SECONDS);
				onStatusChanged.run();
				break;
			case "DeviceList":
				for (JsonElement device : body.getAsJsonArray("Devices"))
				{
					addDevice(device.getAsJsonObject());
				}
				onStatusChanged.run();
				break;
			case "DeviceAdded":
				addDevice(body);
				onStatusChanged.run();
				break;
			case "DeviceRemoved":
				int index = body.get("DeviceIndex").getAsInt();
				devices.remove(index);
				deviceNames.remove(index);
				onStatusChanged.run();
				break;
			case "Error":
				log.debug("Intiface error: {}", body);
				break;
		}
	}

	private void addDevice(JsonObject device)
	{
		int index = device.get("DeviceIndex").getAsInt();
		String name = device.get("DeviceName").getAsString();
		JsonObject messages = device.getAsJsonObject("DeviceMessages");
		if (messages == null || !messages.has("ScalarCmd"))
		{
			return;
		}

		List<Integer> actuators = new ArrayList<>();
		JsonArray scalarCmd = messages.getAsJsonArray("ScalarCmd");
		for (int i = 0; i < scalarCmd.size(); i++)
		{
			JsonObject attributes = scalarCmd.get(i).getAsJsonObject();
			if (attributes.has("ActuatorType") && "Vibrate".equals(attributes.get("ActuatorType").getAsString()))
			{
				actuators.add(i);
			}
		}

		if (!actuators.isEmpty())
		{
			log.info("Intiface device added: {} ({} motors)", name, actuators.size());
			devices.put(index, actuators);
			deviceNames.put(index, name);
		}
	}

	@Override
	public void onClosed(WebSocket webSocket, int code, String reason)
	{
		disconnected(webSocket);
	}

	@Override
	public void onFailure(WebSocket webSocket, Throwable t, Response response)
	{
		log.debug("Intiface connection to {} failed", url, t);
		disconnected(webSocket);
	}

	private synchronized void disconnected(WebSocket webSocket)
	{
		if (webSocket != this.webSocket)
		{
			return;
		}

		this.webSocket = null;
		boolean wasReady = ready;
		ready = false;
		devices.clear();
		deviceNames.clear();
		cancel(pingFuture);
		cancel(stopScanFuture);
		cancel(stopFuture);
		if (wasReady)
		{
			onStatusChanged.run();
		}
		if (!closed)
		{
			reconnectFuture = executor.schedule(this::connect, RECONNECT_SECONDS, TimeUnit.SECONDS);
		}
	}

	private synchronized void send(String type, JsonObject body)
	{
		if (webSocket != null)
		{
			sendOn(webSocket, type, body);
		}
	}

	private void sendOn(WebSocket webSocket, String type, JsonObject body)
	{
		body.addProperty("Id", messageId.getAndIncrement());
		JsonObject message = new JsonObject();
		message.add(type, body);
		JsonArray messages = new JsonArray();
		messages.add(message);
		webSocket.send(gson.toJson(messages));
	}

	private static void cancel(ScheduledFuture<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}

	private static boolean contains(int[] values, int value)
	{
		for (int v : values)
		{
			if (v == value)
			{
				return true;
			}
		}
		return false;
	}
}
