package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.coords.WorldPoint;
import net.runelite.http.api.RuneLiteAPI;
import net.runelite.api.*;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.events.ChatMessage;


@PluginDescriptor(
		name = "Zach's HTTP Server"
)
public class HttpServerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private HttpServer server;
	public String msg;

	@Override
	protected void startUp() throws Exception
	{
		if (server != null) {
			server.stop(0); // Gracefully stop previous instance
		}
		server = HttpServer.create(new InetSocketAddress(8081), 0);
		server.createContext("/stats", this::handleStats);
		server.createContext("/inv", handlerForInv(InventoryID.INVENTORY));
		server.createContext("/equip", handlerForInv(InventoryID.EQUIPMENT));
		server.createContext("/animation", this::handleAnimations);
		server.setExecutor(Executors.newSingleThreadExecutor());
		server.start();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		msg = event.getMessage();
	}

	@Override
	protected void shutDown() throws Exception
	{
		server.stop(1);
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

	private void handleAnimations(HttpExchange exchange) throws IOException {
		int world = client.getWorld();
		Player player = client.getLocalPlayer();
		Actor npc = player.getInteracting();
		Menu menu = client.getMenu();
		MenuEntry[] menuEntries = menu.getMenuEntries();
		String npcName;
		int npcHealth;
		int npcHealth2;
		int health;
		int minHealth = 0;
		int maxHealth = 0;
		int nearbyPlayersCount = getNearbyPlayers();

		if (npc != null)
		{
			npcName = npc.getName();
			npcHealth = npc.getHealthScale();
			npcHealth2 = npc.getHealthRatio();
			health = 0;
			if (npcHealth2 > 0)
			{
				minHealth = 1;
				if (npcHealth > 1)
				{
					if (npcHealth2 > 1)
					{
						// This doesn't apply if healthRatio = 1, because of the special case in the server calculation that
						// health = 0 forces healthRatio = 0 instead of the expected healthRatio = 1
						minHealth = (npcHealth * (npcHealth2 - 1) + npcHealth - 2) / (npcHealth- 1);
					}
					maxHealth = (npcHealth * npcHealth2 - 1) / (npcHealth- 1);
					if (maxHealth > npcHealth)
					{
						maxHealth = npcHealth;
					}
				}
				else
				{
					// If healthScale is 1, healthRatio will always be 1 unless health = 0
					// so we know nothing about the upper limit except that it can't be higher than maxHealth
					maxHealth = npcHealth;
				}
				// Take the average of min and max possible healths
				health = (minHealth + maxHealth + 1) / 2;
			}
		}
		else
		{
			npcName = "null";
			npcHealth = 0;
			npcHealth2 = 0;
			health = 0;
		}
		final List<Integer> idlePoses = Arrays.asList(808, 813, 3418, 10075);

		JsonObject object = new JsonObject();
		JsonObject camera = new JsonObject();
		JsonObject worldPoint = new JsonObject();
		JsonObject mouse = new JsonObject();
		JsonObject hover = new JsonObject();

		if (menuEntries.length > 0){
			for(var i = 0; i < menuEntries.length; i++){
				hover.addProperty(String.valueOf(i),menuEntries[i].getOption());
			}
		}

		object.addProperty("animation", player.getAnimation());
		object.addProperty("animation pose", player.getPoseAnimation());
		boolean isIdle = player.getAnimation() == -1 && idlePoses.contains(player.getPoseAnimation());
		object.addProperty("Is idle", isIdle);
		object.addProperty("run energy", client.getEnergy());
		int specialAttack = client.getVarpValue(300) / 10;
		object.addProperty("latest msg", msg);
		object.addProperty("special attack", specialAttack);
		object.addProperty("game tick", client.getGameCycle());
		object.addProperty("health", client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS));
		object.addProperty("interacting code", String.valueOf(player.getInteracting()));
		object.addProperty("npc name", npcName);
		object.addProperty("npc health ", minHealth);
		mouse.addProperty("x", client.getMouseCanvasPosition().getX());
		mouse.addProperty("y", client.getMouseCanvasPosition().getY());
		worldPoint.addProperty("x", player.getWorldLocation().getX());
		worldPoint.addProperty("y", player.getWorldLocation().getY());
		worldPoint.addProperty("plane", player.getWorldLocation().getPlane());
		worldPoint.addProperty("regionID", player.getWorldLocation().getRegionID());
		worldPoint.addProperty("regionX", player.getWorldLocation().getRegionX());
		worldPoint.addProperty("regionY", player.getWorldLocation().getRegionY());
		camera.addProperty("yaw", client.getCameraYaw());
		camera.addProperty("pitch", client.getCameraPitch());
		camera.addProperty("x", client.getCameraX());
		camera.addProperty("y", client.getCameraY());
		camera.addProperty("z", client.getCameraZ());
		object.add("worldPoint", worldPoint);
		object.add("camera", camera);
		object.add("mouse", mouse);
		object.add("hover",hover);
		object.addProperty("nearbyPlayerCount", nearbyPlayersCount);
		exchange.sendResponseHeaders(200, 0);
		try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody()))
		{
			RuneLiteAPI.GSON.toJson(object, out);
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

	public int getNearbyPlayers()
	{
		int nearbyPlayersCount = 0;
		Player localPlayer = client.getLocalPlayer();
		WorldView worldView = client.getTopLevelWorldView();

		for (Player player : worldView.players())
		{
			if (player == null || player == client.getLocalPlayer())
			{
				continue;
			}

			WorldPoint playerLocation = WorldPoint.fromLocalInstance(client, player.getLocalLocation());
			WorldPoint localLocation = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation());

			if (localLocation.distanceTo(playerLocation) <= 100)
			{
				nearbyPlayersCount = nearbyPlayersCount + 1;
			}
		}

		return nearbyPlayersCount;
	}
}
