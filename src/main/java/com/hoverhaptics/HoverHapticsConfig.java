package com.hoverhaptics;

import lombok.AllArgsConstructor;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(HoverHapticsConfig.GROUP)
public interface HoverHapticsConfig extends Config
{
	String GROUP = "hover-haptics";

	enum Motor
	{
		BOTH,
		LOW_FREQUENCY,
		HIGH_FREQUENCY
	}

	@AllArgsConstructor
	enum Intensity
	{
		OFF("Off"),
		LIGHT("Light"),
		MEDIUM("Medium"),
		STRONG("Strong");

		private final String name;

		@Override
		public String toString()
		{
			return name;
		}
	}

	enum StatusDisplay
	{
		ALWAYS,
		WHEN_NOT_READY,
		NEVER
	}

	@ConfigSection(
		name = "Intiface Central (required)",
		description = "Hover Haptics needs the free Intiface Central app (intiface.com/central) running with its server started. "
			+ "RuneLite can't access controllers itself, so Intiface Central does the actual vibrating.",
		position = -1
	)
	String intifaceSection = "intiface";

	@ConfigSection(
		name = "Intensities",
		description = "Strength and duration of each intensity level",
		position = 10
	)
	String intensitySection = "intensities";

	@ConfigSection(
		name = "Tagged",
		description = "Tagged IDs as id:intensity. Use the right-click menu entry to add or remove them.",
		position = 20,
		closedByDefault = true
	)
	String taggedSection = "tagged";

	@ConfigItem(
		keyName = "markedTiles",
		name = "Marked tiles",
		description = "Vibrate when hovering tiles marked with the Ground Markers plugin",
		position = 0
	)
	default Intensity markedTiles()
	{
		return Intensity.OFF;
	}

	@ConfigItem(
		keyName = "motor",
		name = "Motor",
		description = "Which rumble motor(s) to use. On Xbox controllers low frequency is the heavy (left) motor, high frequency the light (right) one.",
		position = 1
	)
	default Motor motor()
	{
		return Motor.BOTH;
	}

	@ConfigItem(
		keyName = "requireShift",
		name = "Require Shift for menu entry",
		description = "Only show the 'Vibrate on hover' menu entry while Shift is held",
		position = 2
	)
	default boolean requireShift()
	{
		return true;
	}

	@ConfigItem(
		keyName = "intifaceUrl",
		name = "Intiface URL",
		description = "WebSocket address of Intiface Central's server. Requires the free Intiface Central app from intiface.com/central.",
		section = intifaceSection,
		position = -3
	)
	default String intifaceUrl()
	{
		return "ws://127.0.0.1:12345";
	}

	@ConfigItem(
		keyName = "statusDisplay",
		name = "Status infobox",
		description = "When to show the connection status infobox. Hover it for details.",
		section = intifaceSection,
		position = -2
	)
	default StatusDisplay statusDisplay()
	{
		return StatusDisplay.ALWAYS;
	}

	@Range(min = 1, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "lightStrength",
		name = "Light strength",
		description = "Vibration strength of the Light intensity",
		section = intensitySection,
		position = 11
	)
	default int lightStrength()
	{
		return 25;
	}

	@Range(min = 10, max = 2000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "lightDuration",
		name = "Light duration",
		description = "Pulse length of the Light intensity",
		section = intensitySection,
		position = 12
	)
	default int lightDuration()
	{
		return 50;
	}

	@Range(min = 1, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "mediumStrength",
		name = "Medium strength",
		description = "Vibration strength of the Medium intensity",
		section = intensitySection,
		position = 13
	)
	default int mediumStrength()
	{
		return 50;
	}

	@Range(min = 10, max = 2000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "mediumDuration",
		name = "Medium duration",
		description = "Pulse length of the Medium intensity",
		section = intensitySection,
		position = 14
	)
	default int mediumDuration()
	{
		return 80;
	}

	@Range(min = 1, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "strongStrength",
		name = "Strong strength",
		description = "Vibration strength of the Strong intensity",
		section = intensitySection,
		position = 15
	)
	default int strongStrength()
	{
		return 100;
	}

	@Range(min = 10, max = 2000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "strongDuration",
		name = "Strong duration",
		description = "Pulse length of the Strong intensity",
		section = intensitySection,
		position = 16
	)
	default int strongDuration()
	{
		return 150;
	}

	@ConfigItem(
		keyName = "npcIds",
		name = "NPCs",
		description = "Tagged NPC IDs",
		section = taggedSection,
		position = 21
	)
	default String npcIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "objectIds",
		name = "Objects",
		description = "Tagged object IDs",
		section = taggedSection,
		position = 22
	)
	default String objectIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "itemIds",
		name = "Ground items",
		description = "Tagged ground item IDs",
		section = taggedSection,
		position = 23
	)
	default String itemIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "inventoryItemIds",
		name = "Inventory items",
		description = "Tagged item IDs in the inventory, bank, equipment and other item containers",
		section = taggedSection,
		position = 24
	)
	default String inventoryItemIds()
	{
		return "";
	}
}
