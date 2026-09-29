package com.hoverhaptics;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class HoverHapticsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(HoverHapticsPlugin.class);
		RuneLite.main(args);
	}
}
