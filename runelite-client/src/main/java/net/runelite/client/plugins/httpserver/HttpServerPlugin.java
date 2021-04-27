package net.runelite.client.plugins.httpserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientUI;
import net.runelite.http.api.RuneLiteAPI;

@PluginDescriptor(
	name = "HTTP Server"
)
public class HttpServerPlugin extends Plugin
{
	@Inject
	private Client client;

	@com.google.inject.Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	private HttpServer server;

	@Override
	protected void startUp() throws Exception
	{
		server = HttpServer.create(new InetSocketAddress(8080), 0);
		server.createContext("/stats", this::handleStats);
		server.createContext("/game", this::handleGame);
		server.createContext("/player", this::handlePlayer);
		server.createContext("/inv", this::handleInventory);
		server.createContext("/equip", handlerForInv(InventoryID.EQUIPMENT));
		server.setExecutor(Executors.newSingleThreadExecutor());
		server.start();
	}

	@Override
	protected void shutDown() throws Exception
	{
		server.stop(1);
	}

	public void handleGame(HttpExchange exchange) throws IOException
	{
		JsonObject object = new JsonObject();
		object.addProperty("gameX", clientUI.getCanvasOffset().getX());
		object.addProperty("gameY", clientUI.getCanvasOffset().getY());


		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(object, out);
		}
	}

	public void handlePlayer(HttpExchange exchange) throws IOException
	{
		Player player = client.getLocalPlayer();

		JsonObject object = new JsonObject();

		int usedInventorySlots = 0;
		ItemContainer itemContainer = client.getItemContainer(InventoryID.INVENTORY);
		if (itemContainer != null)
			for (Item item:itemContainer.getItems())
				if (item.getQuantity() > 0 && item.getId() > -1) usedInventorySlots++;

		object.addProperty("usedInventorySlots", usedInventorySlots);
		object.addProperty("currentActionAnimation", player.getAnimation());

		String currentPoseAnimation = "";
		switch (player.getPoseAnimation()){
			case 808:
				currentPoseAnimation = "idle";
				break;
			case 819:
				currentPoseAnimation = "walking";
				break;
			case 824:
				currentPoseAnimation = "running";
				break;
		}
		object.addProperty("currentPoseAnimation", currentPoseAnimation);
		object.addProperty("energy", client.getEnergy());

		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(object, out);
		}
	}

	private static WidgetItem getWidgetItem(Widget parentWidget, int idx)
	{
		if (parentWidget.isIf3())
		{
			Widget wi = parentWidget.getChild(idx);
			return new WidgetItem(wi.getItemId(), wi.getItemQuantity(), -1, wi.getBounds(), parentWidget, wi.getBounds());
		}
		else
		{
			return parentWidget.getWidgetItem(idx);
		}
	}

	public void handleStats(HttpExchange exchange) throws IOException
	{
		JsonArray skills = new JsonArray();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}

			JsonObject object = new JsonObject();
			object.addProperty("stat", skill.getName());
			object.addProperty("level", client.getRealSkillLevel(skill));
			object.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
			object.addProperty("xp", client.getSkillExperience(skill));
			skills.add(object);
		}

		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(skills, out);
		}
	}

	public void handleInventory(HttpExchange exchange) throws IOException
	{
		Widget invWidget = client.getWidget(WidgetInfo.INVENTORY);

		JsonArray items = new JsonArray();

		for (int i = 0; i < 28; ++i)
		{
			final WidgetItem targetWidgetItem = getWidgetItem(invWidget, i);
			final Rectangle bounds = targetWidgetItem.getCanvasBounds(false);

			JsonObject object = new JsonObject();
			object.addProperty("id", targetWidgetItem.getId());
			object.addProperty("quantity", targetWidgetItem.getQuantity());
			object.addProperty("x", targetWidgetItem.getCanvasLocation().getX());
			object.addProperty("y", targetWidgetItem.getCanvasLocation().getY());
			items.add(object);

		}

		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(items, out);
		}
	}

	private HttpHandler handlerForInv(InventoryID inventoryID)
	{
		return exchange -> {
			Item[] items = invokeAndWait(() -> {
				ItemContainer itemContainer = client.getItemContainer(inventoryID);
				if (itemContainer != null)
				{
					return itemContainer.getItems();
				}
				return null;
			});

			if (items == null)
			{
				exchange.sendResponseHeaders(204, 0);
				return;
			}

			exchange.sendResponseHeaders(200, 0);
			try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
			{
				RuneLiteAPI.GSON.toJson(items, out);
			}
		};
	}

	private <T> T invokeAndWait(Callable<T> r)
	{
		try
		{
			AtomicReference<T> ref = new AtomicReference<>();
			Semaphore semaphore = new Semaphore(0);
			clientThread.invokeLater(() -> {
				try
				{

					ref.set(r.call());
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
				finally
				{
					semaphore.release();
				}
			});
			semaphore.acquire();
			return ref.get();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
}
