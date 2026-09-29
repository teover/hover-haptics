package com.hoverhaptics;

import java.awt.Color;
import java.awt.image.BufferedImage;
import lombok.Setter;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

@Setter
class StatusInfoBox extends InfoBox
{
	private String text = "";
	private Color textColor = Color.WHITE;
	private String tooltip = "";

	StatusInfoBox(BufferedImage image, Plugin plugin)
	{
		super(image, plugin);
	}

	@Override
	public String getText()
	{
		return text;
	}

	@Override
	public Color getTextColor()
	{
		return textColor;
	}

	@Override
	public String getTooltip()
	{
		return tooltip;
	}
}
