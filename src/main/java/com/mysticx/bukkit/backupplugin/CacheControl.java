package com.mysticx.bukkit.backupplugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * CacheControl is to be used as singleton.
 *
 * Cache is located on hdd and contains all map data. cache-lifetime determines
 * when cache becomes obsolete CacheControl needs to ensure that no concurrent
 * access to the cache is granted
 *
 * @author MysticX
 *
 */
public final class CacheControl {

    // singleton
    private static final CacheControl cc = new CacheControl();;

    /**
     * Cache cleanup task for timer
     *
     * @author MysticX
     *
     */
    private class CacheCleanerTask extends TimerTask {

        @Override
        public void run() {
            deleteCache();
            timer.cancel();
            MessageHandler.log(Level.INFO, "Cache lifetime ended.");
        }

    }

    // helper stuff
    private IOHelper iohelper;
    private File cache;
    private File world;

    // cache lifetime
    private int cacheLifetime;
    private int cacheHistory;
    private TimeUnit tu;
    private Timer timer;
    private Lock lock;

    /**
     * Private constructor, use getInstance()
     */
    private CacheControl() {
        this.iohelper = IOHelper.getInstance();
        this.cache = null;
        this.world = null;
        this.tu = TimeUnit.MINUTES;
        this.cacheLifetime = 30;
        this.cacheHistory = 5;
        this.timer = new Timer();
        this.lock = new ReentrantLock();
    }

    /**
     *
     * @return instance of CacheControl
     */
    public static CacheControl getInstance() {
        return cc;
    }

    /**
     * Sets TimeUnit
     *
     * @param tu
     */
    public void setTimeUnit(TimeUnit tu) {
        this.tu = tu;
    }

    /**
     * Sets TimeUnit
     *
     * @param tu
     */
    public void setTimeUnit(String tu) {
        try {
            TimeUnit timeunit = TimeUnit.valueOf(tu);
            setTimeUnit(timeunit);
        } catch (Exception e) {
            MessageHandler.warning("Failed to parse time-unit, using default.");
        }
    }

    /**
     * Sets temp/cache dir
     *
     * @param tempdir
     */
    public void setTempDir(File tempdir) {
        this.cache = tempdir;
    }

    /**
     * Sets world name and path
     *
     * @param worldname
     */
    public void setWorld(String worldname) {
        this.world = new File(worldname);
    }

    /**
     * @return file object representing the current world
     */
    public File getWorld() {
        return world;
    }

    /**
     * Returns cache lock
     *
     * @return lock
     */
    public Lock getLock() {
        return lock;
    }

    /**
     * Sets cache lifetime
     *
     * @param lifetime
     *            according to TimeUnit
     */
    public void setCacheLifetime(int lifetime) {
        this.cacheLifetime = lifetime;
    }

    /**
     * Sets cache history
     *
     * @param history
     *            number of backups to keep
     */
    public void setCacheHistory(int history) {
        this.cacheHistory = history;
    }

    /**
     * Checks if cache exists and is up to date
     *
     * @return true, if cache is too old
     */
    private boolean isCacheObsolete() {
        return (!this.cache.exists() || (System.currentTimeMillis() - this.cache.lastModified()) > tu.toMillis(this.cacheLifetime));
    }

    /**
     * Resets timer
     */
    private void scheduleTimer() {
        timer = new Timer();
        timer.schedule(new CacheCleanerTask(), tu.toMillis(this.cacheLifetime));
        MessageHandler.log(Level.FINEST, "CacheCleaner was scheduled.");
    }

    /**
     * Deletes the temp dir / cache dir
     *
     * @return true if successful
     */
    private boolean deleteCache() {

        if (!cache.exists()) {
            return true;
        }

        MessageHandler.log(Level.FINEST, "deleteCache() obtaining lock..");
        lock.lock();
        MessageHandler.log(Level.INFO, "Deleting cache, might be obsolete.");

        try {
            if (!iohelper.deleteDirectory(cache)) {
                MessageHandler.log(Level.WARNING, "Failed to delete temp folder.");
                return false;
            }
        } finally {
            lock.unlock();
            MessageHandler.log(Level.FINEST, "deleteCache() unlocked..");
        }

        return true;
    }

    /**
     * Returns cache
     *
     * @param force
     *            initializes cache rebuild
     * @return cache
     */
    public synchronized File getCache(boolean force) {
        // return existing cache
        if (!force && !isCacheObsolete()) {
            MessageHandler.log(Level.FINEST, "Cache still up to date!");
            return cache;
        } else {
            // cancel timer
            if (timer != null) {
                timer.cancel();
            }
            // rebuild cache
            if (rebuildCache()) {
                // setup timer
                scheduleTimer();
                return cache;
            } else {
                MessageHandler.log(Level.WARNING, "Cache couldn't be rebuilt!");
                return null;
            }
        }
    }

    /**
     * Initializes map cache on hdd
     *
     * @return Cache
     */
    private boolean rebuildCache() {
        // check if world exists
        if (!world.exists()) {
            MessageHandler.log(Level.WARNING, "World path doesn't exist!");
            return false;
        }

        MessageHandler.log(Level.INFO, "Rebuilding Cache. This can take several minutes, depending on the world size.");

        if (cache.exists() && !deleteCache()) {
            return false;
        }

        try {
            MessageHandler.log(Level.FINEST, "rebuildCache() obtaining lock..");
            lock.lock();
            MessageHandler.log(Level.FINEST, "rebuildCache() got lock, copy dir..");
            // copy world to temp/cache
            iohelper.copyDir(world, cache);
        } catch (FileNotFoundException e) {
            MessageHandler.log(Level.SEVERE, "Error rebuilding cache: ", e);
            return false;
        } catch (IOException e) {
            MessageHandler.log(Level.SEVERE, "Error rebuilding cache: ", e);
            return false;
        } finally {
            lock.unlock();
            MessageHandler.log(Level.FINEST, "rebuildCache() unlocked..");
        }

        return true;

    }

    /**
     * Persists cache to zip
     */
    public boolean persistCache(File outputFile, boolean force) {
        MessageHandler.log(Level.FINE, "Persisting cache / creating zip file..");
        try {
            File currentCache = this.getCache(force);
            MessageHandler.log(Level.FINEST, "persistCache() got cache, obtaining lock..");
            lock.lock();
            MessageHandler.log(Level.FINEST, "persistCache() got lock, starting zip operation..");
            iohelper.zipDirectory(currentCache, outputFile);
            MessageHandler.log(Level.FINEST, "persistCache() finished zip operation..");

            if (cacheHistory > 0) {
                iohelper.deleteOldFiles(outputFile.getParentFile(), this.world.getName(), cacheHistory);
            }
            return true;
        } catch (IOException e) {
            MessageHandler.log(Level.SEVERE, "Error while zipping temp folder!", e);
        } finally {
            lock.unlock();
            MessageHandler.log(Level.FINEST, "persistCache() unlocked..");
        }
        return false;
    }

}
