package com.xxmicloxx.NoteBlockAPI;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import com.xxmicloxx.NoteBlockAPI.songplayer.SongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.MathUtils;
import com.xxmicloxx.NoteBlockAPI.utils.Updater;

/**
 * Main class; contains methods for playing and adjusting songs for players
 */
public class NoteBlockAPI extends JavaPlugin {

	private static NoteBlockAPI plugin;
	
	private Map<UUID, ArrayList<SongPlayer>> playingSongs = 
			Collections.synchronizedMap(new HashMap<UUID, ArrayList<SongPlayer>>());
	private Map<UUID, Byte> playerVolume = Collections.synchronizedMap(new HashMap<UUID, Byte>());

	private boolean disabling = false;
	
	private HashMap<Plugin, Boolean> dependentPlugins = new HashMap<>();

	/**
	 * Returns true if a Player is currently receiving a song
	 * @param player
	 * @return is receiving a song
	 */
	public static boolean isReceivingSong(Player player) {
		return ((plugin.playingSongs.get(player.getUniqueId()) != null) 
				&& (!plugin.playingSongs.get(player.getUniqueId()).isEmpty()));
	}

	/**
	 * Stops the song for a Player
	 * @param player
	 */
	public static void stopPlaying(Player player) {
		if (plugin.playingSongs.get(player.getUniqueId()) == null) {
			return;
		}
		for (SongPlayer songPlayer : plugin.playingSongs.get(player.getUniqueId())) {
			songPlayer.removePlayer(player);
		}
	}

	/**
	 * Sets the volume for a given Player
	 * @param player
	 * @param volume
	 */
	public static void setPlayerVolume(Player player, byte volume) {
		plugin.playerVolume.put(player.getUniqueId(), volume);
	}

	/**
	 * Gets the volume for a given Player
	 * @param player
	 * @return volume (byte)
	 */
	public static byte getPlayerVolume(Player player) {
		Byte byteObj = plugin.playerVolume.get(player.getUniqueId());
		if (byteObj == null) {
			byteObj = 100;
			plugin.playerVolume.put(player.getUniqueId(), byteObj);
		}
		return byteObj;
	}
	
	public static ArrayList<SongPlayer> getSongPlayersByPlayer(Player player){
		return getSongPlayersByPlayer(player.getUniqueId());
	}
	
	public static ArrayList<SongPlayer> getSongPlayersByPlayer(UUID player){
		return plugin.playingSongs.get(player);
	}
	
	public static void setSongPlayersByPlayer(Player player, ArrayList<SongPlayer> songs){
		setSongPlayersByPlayer(player.getUniqueId(), songs);
	}
	
	public static void setSongPlayersByPlayer(UUID player, ArrayList<SongPlayer> songs){
		plugin.playingSongs.put(player, songs);
	}

	@Override
	public void onEnable() {
		plugin = this;
		
		for (Plugin pl : getServer().getPluginManager().getPlugins()){
			if (pl.getDescription().getDepend().contains("NoteBlockAPI") || pl.getDescription().getSoftDepend().contains("NoteBlockAPI")){
				dependentPlugins.put(pl, false);
			}
		}
		
		Metrics metrics = new Metrics(this);	
		
		new NoteBlockPlayerMain().onEnable();
		
		getServer().getScheduler().runTaskLater(this, new Runnable() {
			
			@Override
			public void run() {
				Plugin[] plugins = getServer().getPluginManager().getPlugins();
		        Type[] types = new Type[]{PlayerRangeStateChangeEvent.class, SongDestroyingEvent.class, SongEndEvent.class, SongStoppedEvent.class };
		        for (Plugin plugin : plugins) {
		            ArrayList<RegisteredListener> rls = HandlerList.getRegisteredListeners(plugin);
		            for (RegisteredListener rl : rls) {
		                Method[] methods = rl.getListener().getClass().getDeclaredMethods();
		                for (Method m : methods) {
		                    Type[] params = m.getParameterTypes();
		                    param:
		                    for (Type paramType : params) {
		                    	for (Type type : types){
		                    		if (paramType.equals(type)) {
		                    			dependentPlugins.put(plugin, true);
		                    			break param;
		                    		}
		                    	}
		                    }
		                }

		            }
		        }
		        
		        metrics.addCustomChart(new Metrics.DrilldownPie("deprecated", () -> {
			        Map<String, Map<String, Integer>> map = new HashMap<>();
			        for (Plugin pl : dependentPlugins.keySet()){
			        	String deprecated = dependentPlugins.get(pl) ? "yes" : "no";
			        	Map<String, Integer> entry = new HashMap<>();
				        entry.put(pl.getDescription().getFullName(), 1);
				        map.put(deprecated, entry);
			        }
			        return map;
			    }));
			}
		}, 1);
		
		getServer().getScheduler().runTaskLaterAsynchronously(this, new Runnable() {
			
			@Override
			public void run() {
				try {
					if (Updater.checkUpdate("19287", getDescription().getVersion())){
						Bukkit.getLogger().info(String.format("[%s] New update available!", plugin.getDescription().getName()));
					}
				} catch (IOException e) {
					Bukkit.getLogger().info(String.format("[%s] Cannot receive update from Spigot resource page!", plugin.getDescription().getName()));
				}
			}
		}, 20*10);
		
		new MathUtils();
	}

	@Override
	public void onDisable() {    	
		disabling = true;
		Bukkit.getScheduler().cancelTasks(this);
		NoteBlockPlayerMain.plugin.onDisable();
	}

	public void doSync(Runnable runnable) {
		getServer().getScheduler().runTask(this, runnable);
	}

	public void doAsync(Runnable runnable) {
		getServer().getScheduler().runTaskAsynchronously(this, runnable);
	}

	private ExecutorService submit = Executors.newSingleThreadExecutor(
			new ThreadFactoryBuilder().setNameFormat("NoteBlockAPI Music #%1$d").build()
	);
	private Future<?> commonTask;

	private List<Delayed> delayeds = new LinkedList<>();

	{
		this.runCommonTaskIfNotExists();
	}

	private void runCommonTaskIfNotExists() {
		if (commonTask != null && !commonTask.isDone()) {
			return;
		}

		commonTask = this.submit.submit(() -> {
			while (true) {
				if (delayeds.isEmpty()) {
					break;
				}

				try {
					long minNextTime = -1;
					for (Iterator<Delayed> iterator = delayeds.iterator(); iterator.hasNext(); ) {
						Delayed delayed = iterator.next();
						if (delayed.isDone()) {
							iterator.remove();
						} else {
							long current = System.currentTimeMillis();
							if (current >= delayed.nextTimePlay()) {
								delayed.play();
							}
							minNextTime = minNextTime == -1 ?
									delayed.nextTimePlay() :
									Math.min(minNextTime, delayed.nextTimePlay());
						}
					}

					long current = System.currentTimeMillis();
					if (minNextTime > current) {
						Thread.sleep(minNextTime - current);
					}
				} catch (InterruptedException e) {
					break;
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	public void doDelayed(Delayed delayed) {
		this.delayeds.add(delayed);
		this.runCommonTaskIfNotExists();
	}

	public interface Delayed {
		void play() throws InterruptedException;

		long nextTimePlay();

		boolean isDone();
	}

	public boolean isDisabling() {
		return disabling;
	}
	
	public static NoteBlockAPI getAPI(){
		return plugin;
	}
	
	protected void handleDeprecated(StackTraceElement[] ste){
		int pom = 1;
		String clazz = ste[pom].getClassName();
		while (clazz.startsWith("com.xxmicloxx.NoteBlockAPI")){
			pom++;
			clazz = ste[pom].getClassName();
		}
		String[] packageParts = clazz.split("\\.");
		ArrayList<Plugin> plugins = new ArrayList<Plugin>();
		plugins.addAll(dependentPlugins.keySet());
		
		ArrayList<Plugin> notResult = new ArrayList<Plugin>();
		parts:
		for (int i = 0; i < packageParts.length - 1; i++){
			
			for (Plugin pl : plugins){
				if (notResult.contains(pl)){ continue;}
				if (plugins.size() - notResult.size() == 1){
					break parts;
				}
				String[] plParts = pl.getDescription().getMain().split("\\.");
				if (!packageParts[i].equalsIgnoreCase(plParts[i])){
					notResult.add(pl);
					continue;
				}
			}
			plugins.removeAll(notResult);
			notResult.clear();
		}
		
		plugins.removeAll(notResult);
		notResult.clear();
		if (plugins.size() == 1){
			dependentPlugins.put(plugins.get(0), true);
		}
	}
	
}
