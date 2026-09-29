package com.hoverhaptics;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import com.hoverhaptics.HoverHapticsConfig.Intensity;
import java.awt.Color;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Hover Haptics",
	description = "Vibrates your gamepad (through the Intiface Central app) when hovering tagged NPCs, objects, items and marked tiles",
	tags = {"haptics", "haptic", "gamepad", "controller", "joystick", "joypad", "xbox", "xinput", "playstation", "dualshock", "dualsense", "ps4", "ps5", "switch pro", "steam deck", "vibration", "vibrate", "rumble", "force feedback", "hover", "accessibility", "intiface"}
)
public class HoverHapticsPlugin extends Plugin
{
	private static final String TAG = "Vibrate on hover";
	private static final String REMOVE = "Remove";
	private static final Color CURRENT_COLOR = new Color(0x00FF00);

	private static final String GROUND_MARKER_GROUP = "groundMarker";
	private static final String GROUND_MARKER_REGION_PREFIX = "region_";
	private static final String GROUND_MARKER_PLUGIN = "groundmarkerplugin";

	@AllArgsConstructor
	private enum Kind
	{
		NPC("npcIds"),
		OBJECT("objectIds"),
		GROUND_ITEM("itemIds"),
		INVENTORY_ITEM("inventoryItemIds");

		private final String configKey;
	}

	@AllArgsConstructor
	private enum Status
	{
		DISCONNECTED("!", Color.RED),
		NO_DEVICE("!", Color.YELLOW),
		READY("OK", Color.GREEN);

		private final String text;
		private final Color color;
	}

	@Value
	private static class Hover
	{
		String key;
		Intensity intensity;
	}

	/**
	 * A tile as stored by the Ground Markers plugin.
	 */
	private static class GroundMarkerPoint
	{
		int regionX;
		int regionY;
		int z;
	}

	@Inject
	private Client client;

	@Inject
	private HoverHapticsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientThread clientThread;

	@Inject
	private InfoBoxManager infoBoxManager;

	private final Map<Kind, Map<Integer, Intensity>> tagged = new EnumMap<>(Kind.class);
	/** region id -> packed region tiles marked by Ground Markers, loaded lazily */
	private final Map<Integer, Set<Integer>> markedTiles = new HashMap<>();
	private IntifaceClient intiface;
	private String lastHovered;
	private StatusInfoBox statusInfoBox;
	private Status status;

	@Override
	protected void startUp()
	{
		statusInfoBox = new StatusInfoBox(ImageUtil.loadImageResource(getClass(), "gamepad.png"), this);
		loadTags();
		connect();
	}

	@Override
	protected void shutDown()
	{
		infoBoxManager.removeInfoBox(statusInfoBox);
		statusInfoBox = null;
		status = null;
		intiface.close();
		intiface = null;
		tagged.clear();
		markedTiles.clear();
		lastHovered = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GROUND_MARKER_GROUP.equals(event.getGroup()))
		{
			markedTiles.clear();
			return;
		}

		if (!HoverHapticsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("intifaceUrl".equals(event.getKey()))
		{
			intiface.close();
			connect();
		}
		else if ("statusDisplay".equals(event.getKey()))
		{
			clientThread.invokeLater(this::updateStatus);
		}
		else
		{
			loadTags();
		}
	}

	private void connect()
	{
		intiface = new IntifaceClient(okHttpClient, gson, executor, config.intifaceUrl().trim(),
			() -> clientThread.invokeLater(this::updateStatus));
		intiface.connect();
		clientThread.invokeLater(this::updateStatus);
	}

	private void updateStatus()
	{
		if (intiface == null)
		{
			return;
		}

		Status newStatus = !intiface.isConnected() ? Status.DISCONNECTED
			: intiface.deviceCount() == 0 ? Status.NO_DEVICE
			: Status.READY;
		if (status != null && newStatus != status && client.getGameState() == GameState.LOGGED_IN)
		{
			chat(statusMessage(newStatus));
		}
		status = newStatus;

		statusInfoBox.setText(status.text);
		statusInfoBox.setTextColor(status.color);
		statusInfoBox.setTooltip("Hover Haptics</br>" + statusMessage(status));

		boolean show = config.statusDisplay() == HoverHapticsConfig.StatusDisplay.ALWAYS
			|| (config.statusDisplay() == HoverHapticsConfig.StatusDisplay.WHEN_NOT_READY && status != Status.READY);
		infoBoxManager.removeInfoBox(statusInfoBox);
		if (show)
		{
			infoBoxManager.addInfoBox(statusInfoBox);
		}
	}

	private String statusMessage(Status status)
	{
		switch (status)
		{
			case DISCONNECTED:
				return "Not connected to Intiface Central at " + intiface.getUrl() + ". Start Intiface Central and press Start Server.";
			case NO_DEVICE:
				return "Connected to Intiface Central, but no controller found. Connect your controller and press Start Scanning in Intiface Central.";
			default:
				return "Connected to Intiface Central: " + String.join(", ", intiface.deviceNames());
		}
	}

	private void chat(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Hover Haptics: " + message, null);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (config.requireShift() && !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		MenuEntry entry = event.getMenuEntry();
		Kind kind;
		int id;
		switch (entry.getType())
		{
			case EXAMINE_NPC:
				NPC npc = entry.getNpc();
				if (npc == null)
				{
					return;
				}
				kind = Kind.NPC;
				id = npc.getId();
				break;
			case EXAMINE_OBJECT:
				kind = Kind.OBJECT;
				id = entry.getIdentifier();
				break;
			case EXAMINE_ITEM_GROUND:
				kind = Kind.GROUND_ITEM;
				id = entry.getIdentifier();
				break;
			case EXAMINE_ITEM:
			case CC_OP:
			case CC_OP_LOW_PRIORITY:
				if (!"Examine".equals(Text.removeTags(entry.getOption())) || entry.getItemId() <= 0)
				{
					return;
				}
				kind = Kind.INVENTORY_ITEM;
				id = entry.getItemId();
				break;
			default:
				return;
		}

		Intensity current = tagged.get(kind).get(id);
		MenuEntry parent = client.getMenu().createMenuEntry(-1)
			.setOption(TAG)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE);

		// Entries created later end up higher in the menu
		Menu subMenu = parent.createSubMenu();
		if (current != null)
		{
			subMenu.createMenuEntry(-1)
				.setOption(REMOVE)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setTag(kind, id, null));
		}
		for (Intensity intensity : new Intensity[]{Intensity.STRONG, Intensity.MEDIUM, Intensity.LIGHT})
		{
			String option = intensity.toString();
			subMenu.createMenuEntry(-1)
				.setOption(intensity == current ? ColorUtil.wrapWithColorTag(option, CURRENT_COLOR) : option)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setTag(kind, id, intensity));
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.isMenuOpen())
		{
			return;
		}

		Hover hovered = findHovered();
		String key = hovered == null ? null : hovered.getKey();
		if (key != null && !key.equals(lastHovered))
		{
			vibrate(hovered.getIntensity());
		}
		lastHovered = key;
	}

	/**
	 * @return the tagged thing under the mouse, or null if none
	 */
	private Hover findHovered()
	{
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		boolean overScene = false;
		// The last entry is the top of the menu, i.e. the left-click option
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			Intensity intensity;
			switch (entry.getType())
			{
				case WALK:
					overScene = true;
					break;
				case NPC_FIRST_OPTION:
				case NPC_SECOND_OPTION:
				case NPC_THIRD_OPTION:
				case NPC_FOURTH_OPTION:
				case NPC_FIFTH_OPTION:
				case WIDGET_TARGET_ON_NPC:
				case EXAMINE_NPC:
					NPC npc = entry.getNpc();
					if (npc != null && (intensity = tagged.get(Kind.NPC).get(npc.getId())) != null)
					{
						return new Hover("npc:" + npc.getIndex(), intensity);
					}
					break;
				case GAME_OBJECT_FIRST_OPTION:
				case GAME_OBJECT_SECOND_OPTION:
				case GAME_OBJECT_THIRD_OPTION:
				case GAME_OBJECT_FOURTH_OPTION:
				case GAME_OBJECT_FIFTH_OPTION:
				case WIDGET_TARGET_ON_GAME_OBJECT:
				case EXAMINE_OBJECT:
					if ((intensity = tagged.get(Kind.OBJECT).get(entry.getIdentifier())) != null)
					{
						return new Hover("obj:" + entry.getIdentifier() + ":" + entry.getParam0() + ":" + entry.getParam1(), intensity);
					}
					break;
				case GROUND_ITEM_FIRST_OPTION:
				case GROUND_ITEM_SECOND_OPTION:
				case GROUND_ITEM_THIRD_OPTION:
				case GROUND_ITEM_FOURTH_OPTION:
				case GROUND_ITEM_FIFTH_OPTION:
				case WIDGET_TARGET_ON_GROUND_ITEM:
				case EXAMINE_ITEM_GROUND:
					if ((intensity = tagged.get(Kind.GROUND_ITEM).get(entry.getIdentifier())) != null)
					{
						return new Hover("item:" + entry.getIdentifier() + ":" + entry.getParam0() + ":" + entry.getParam1(), intensity);
					}
					break;
				default:
					int itemId = entry.getItemId();
					if (itemId > 0 && (intensity = tagged.get(Kind.INVENTORY_ITEM).get(itemId)) != null)
					{
						// param1 is the container widget, param0 the slot
						return new Hover("inv:" + entry.getParam1() + ":" + entry.getParam0(), intensity);
					}
			}
		}

		return overScene ? findHoveredTile() : null;
	}

	private Hover findHoveredTile()
	{
		Intensity intensity = config.markedTiles();
		if (intensity == Intensity.OFF
			|| "false".equals(configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, GROUND_MARKER_PLUGIN)))
		{
			return null;
		}

		Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
		if (tile == null)
		{
			return null;
		}

		WorldPoint point = WorldPoint.fromLocalInstance(client, tile.getLocalLocation(), tile.getPlane());
		Set<Integer> marked = markedTiles.computeIfAbsent(point.getRegionID(), this::loadMarkedTiles);
		if (!marked.contains(packTile(point.getRegionX(), point.getRegionY(), point.getPlane())))
		{
			return null;
		}
		return new Hover("tile:" + point.getX() + ":" + point.getY() + ":" + point.getPlane(), intensity);
	}

	private Set<Integer> loadMarkedTiles(int regionId)
	{
		Set<Integer> tiles = new HashSet<>();
		String json = configManager.getConfiguration(GROUND_MARKER_GROUP, GROUND_MARKER_REGION_PREFIX + regionId);
		if (json == null || json.isEmpty())
		{
			return tiles;
		}

		try
		{
			GroundMarkerPoint[] points = gson.fromJson(json, GroundMarkerPoint[].class);
			for (GroundMarkerPoint point : points)
			{
				tiles.add(packTile(point.regionX, point.regionY, point.z));
			}
		}
		catch (JsonSyntaxException e)
		{
			log.debug("Unable to parse ground markers for region {}", regionId, e);
		}
		return tiles;
	}

	private static int packTile(int regionX, int regionY, int plane)
	{
		return plane << 12 | regionX << 6 | regionY;
	}

	private void vibrate(Intensity intensity)
	{
		int strength;
		int duration;
		switch (intensity)
		{
			case LIGHT:
				strength = config.lightStrength();
				duration = config.lightDuration();
				break;
			case MEDIUM:
				strength = config.mediumStrength();
				duration = config.mediumDuration();
				break;
			case STRONG:
				strength = config.strongStrength();
				duration = config.strongDuration();
				break;
			default:
				return;
		}

		int[] motors;
		switch (config.motor())
		{
			case LOW_FREQUENCY:
				motors = new int[]{0};
				break;
			case HIGH_FREQUENCY:
				motors = new int[]{1};
				break;
			default:
				motors = null;
		}
		intiface.pulse(strength / 100.0, motors, duration);
	}

	/**
	 * @param intensity the new intensity, or null to untag
	 */
	private void setTag(Kind kind, int id, Intensity intensity)
	{
		Map<Integer, Intensity> tags = new HashMap<>(tagged.get(kind));
		if (intensity == null)
		{
			tags.remove(id);
		}
		else
		{
			tags.put(id, intensity);
			warnIfNoDevice();
		}

		String csv = tags.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(e -> e.getKey() + ":" + e.getValue().name().toLowerCase())
			.collect(Collectors.joining(","));
		// Triggers ConfigChanged, which reloads the tags
		configManager.setConfiguration(HoverHapticsConfig.GROUP, kind.configKey, csv);
	}

	private void warnIfNoDevice()
	{
		if (status != Status.READY)
		{
			chat(statusMessage(status == null ? Status.DISCONNECTED : status));
		}
	}

	private void loadTags()
	{
		tagged.put(Kind.NPC, parseTags(config.npcIds()));
		tagged.put(Kind.OBJECT, parseTags(config.objectIds()));
		tagged.put(Kind.GROUND_ITEM, parseTags(config.itemIds()));
		tagged.put(Kind.INVENTORY_ITEM, parseTags(config.inventoryItemIds()));
	}

	/**
	 * Parses "id:intensity" pairs. A bare id means medium intensity.
	 */
	private static Map<Integer, Intensity> parseTags(String csv)
	{
		Map<Integer, Intensity> tags = new HashMap<>();
		for (String s : Text.fromCSV(csv))
		{
			String[] parts = s.split(":", 2);
			try
			{
				Intensity intensity = parts.length > 1 ? Intensity.valueOf(parts[1].trim().toUpperCase()) : Intensity.MEDIUM;
				if (intensity != Intensity.OFF)
				{
					tags.put(Integer.parseInt(parts[0].trim()), intensity);
				}
			}
			catch (IllegalArgumentException e)
			{
				log.debug("Ignoring invalid tag {}", s);
			}
		}
		return tags;
	}

	@Provides
	HoverHapticsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HoverHapticsConfig.class);
	}
}
