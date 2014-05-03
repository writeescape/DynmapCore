package org.dynmap.storage.filetree;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapType;
import org.dynmap.MapType.ImageFormat;
import org.dynmap.MapType.ImageVariant;
import org.dynmap.debug.Debug;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.storage.MapStorageTileEnumCB;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;

public class FileTreeMapStorage extends MapStorage {
    private static Object lock = new Object();
    private static HashMap<String, Integer> filelocks = new HashMap<String, Integer>();
    private static final Integer WRITELOCK = new Integer(-1);
    
    private File baseTileDir;
    private TileHashManager hashmap;
    
    public class StorageTile extends MapStorageTile {
        private final String baseFilename;
        private final String uri;
        private File f; // cached file
        private ImageFormat f_fmt;
        
        StorageTile(DynmapWorld world, MapType map, int x, int y,
                int zoom, ImageVariant var) {
            super(world, map, x, y, zoom, var);
            String baseURI;
            if (zoom > 0) {
                baseURI = map.getPrefix() + var.variantSuffix + "/"+ (x >> 5) + "_" + (y >> 5) + "/" + "zzzzzzzzzzzzzzzz".substring(0, zoom) + "_" + x + "_" + y;
            }
            else {
                baseURI = map.getPrefix() + var.variantSuffix + "/"+ (x >> 5) + "_" + (y >> 5) + "/" + x + "_" + y;
            }
            baseFilename = world.getName() + "/" + baseURI;
            uri = baseURI + "." + map.getImageFormat().getFileExt();
        }
        private File getTileFile(ImageFormat fmt) {
            if ((f == null) || (fmt != f_fmt)) {
                f = new File(baseTileDir, baseFilename + "." + fmt.getFileExt());
                f_fmt = fmt;
            }
            return f;
        }
        private File getTileFile() {
            ImageFormat fmt = map.getImageFormat();
            File ff = getTileFile(fmt);
            if (ff.exists() == false) {
                if (fmt == ImageFormat.FORMAT_PNG) {
                    fmt = ImageFormat.FORMAT_JPG;
                }
                else {
                    fmt = ImageFormat.FORMAT_PNG;
                }
                ff = getTileFile(fmt);
            }
            return ff;
        }
        private File getTileFileAltFormat() {
            ImageFormat fmt = map.getImageFormat();
            if (fmt == ImageFormat.FORMAT_PNG) {
                fmt = ImageFormat.FORMAT_JPG;
            }
            else {
                fmt = ImageFormat.FORMAT_PNG;
            }
            return getTileFile(fmt);
        }
        @Override
        public boolean exists() {
            File ff = getTileFile();
            return ff.isFile() && ff.canRead();
        }

        @Override
        public boolean matchesHashCode(long hash) {
            File ff = getTileFile(map.getImageFormat());
            return ff.isFile() && ff.canRead() && (hash == hashmap.getImageHashCode(world.getName() + "." + map.getPrefix(), null, x, y));
        }

        @Override
        public TileRead read() {
            ImageFormat fmt = map.getImageFormat();
            File ff = getTileFile(fmt);
            if (ff.exists() == false) { // Fallback and try to read other format
                if (fmt == ImageFormat.FORMAT_PNG) {
                    fmt = ImageFormat.FORMAT_JPG;
                }
                else {
                    fmt = ImageFormat.FORMAT_PNG;
                }
                ff = getTileFile(fmt);
            }
            if (ff.isFile()) {
                TileRead tr = new TileRead();
                byte[] buf = new byte[(int) ff.length()];
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(ff);
                    fis.read(buf, 0, buf.length);   // Read whole thing
                } catch (IOException iox) {
                    Log.info("read (" + ff.getPath() + ") failed = " + iox.getMessage());
                    return null;
                } finally {
                    if (fis != null) {
                        try { fis.close(); } catch (IOException iox) {}
                        fis = null;
                    }
                }
                tr.image = new BufferInputStream(buf);
                tr.format = fmt;
                tr.hashCode = hashmap.getImageHashCode(world.getName() + "." + map.getPrefix(), null, x, y);
                return tr;
            }
            return null;
        }

        private static final int MAX_WRITE_RETRIES = 6;

        @Override
        public boolean write(long hash, BufferOutputStream encImage) {
            File ff = getTileFile(map.getImageFormat());
            File ffalt = getTileFileAltFormat();
            File ffpar = ff.getParentFile();
            // Always clean up old alternate file, if it exsits
            if (ffalt.exists()) {
                ffalt.delete();
            }
            if (encImage == null) { // Delete?
                ff.delete();
                hashmap.updateHashCode(world.getName() + "." + map.getPrefix(), null, x, y, -1);
                // Signal update for zoom out
                if (zoom == 0) {
                    world.enqueueZoomOutUpdate(this);
                }
                return true;
            }
            if (ffpar.exists() == false) {
                ffpar.mkdirs();
            }
            File fnew = new File(ff.getPath() + ".new");
            File fold = new File(ff.getPath() + ".old");
            boolean done = false;
            int retrycnt = 0;
            while(!done) {
                RandomAccessFile f = null;
                try {
                    f = new RandomAccessFile(fnew, "rw");
                    f.write(encImage.buf, 0, encImage.len);
                    done = true;
                } catch (IOException fnfx) {
                    if(retrycnt < MAX_WRITE_RETRIES) {
                        Debug.debug("Image file " + ff.getPath() + " - unable to write - retry #" + retrycnt);
                        try { Thread.sleep(50 << retrycnt); } catch (InterruptedException ix) { return false; }
                        retrycnt++;
                    }
                    else {
                        Log.info("Image file " + ff.getPath() + " - unable to write - failed");
                        return false;
                    }
                } finally {
                    if(f != null) {
                        try { f.close(); } catch (IOException iox) { done = false; }
                    }
                    if(done) {
/*TODO:                        if (preUpdateCommand != null && !preUpdateCommand.isEmpty()) {
                            try {
                                new ProcessBuilder(preUpdateCommand, fnew.getAbsolutePath()).start().waitFor();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        */
                        ff.renameTo(fold);
                        fnew.renameTo(ff);
                        fold.delete();
/*TODO                        if (postUpdateCommand != null && !postUpdateCommand.isEmpty()) {
                            try {
                                new ProcessBuilder(postUpdateCommand, fname.getAbsolutePath()).start().waitFor();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
*/                            
                    }
                }            
            }
            hashmap.updateHashCode(world.getName() + "." + map.getPrefix(), null, x, y, hash);
            // Signal update for zoom out
            if (zoom == 0) {
                world.enqueueZoomOutUpdate(this);
            }
            return true;
        }

        @Override
        public boolean getWriteLock() {
            synchronized(lock) {
                boolean got_lock = false;
                while(!got_lock) {
                    Integer lockcnt = filelocks.get(baseFilename);    /* Get lock count */
                    if(lockcnt != null) {   /* If any locks, can't get write lock */
                        try {
                            lock.wait(); 
                        } catch (InterruptedException ix) {
                            Log.severe("getWriteLock(" + baseFilename + ") interrupted");
                            return false;
                        }
                    }
                    else {
                        filelocks.put(baseFilename, WRITELOCK);
                        got_lock = true;
                    }
                }
            }
            return true;
        }

        @Override
        public void releaseWriteLock() {
            synchronized(lock) {
                Integer lockcnt = filelocks.get(baseFilename);    /* Get lock count */
                if(lockcnt == null)
                    Log.severe("releaseWriteLock(" + baseFilename + ") on unlocked file");
                else if(lockcnt.equals(WRITELOCK)) {
                    filelocks.remove(baseFilename);   /* Remove lock */
                    lock.notifyAll();   /* Wake up folks waiting for locks */
                }
                else
                    Log.severe("releaseWriteLock(" + baseFilename + ") on read-locked file");
            }
        }

        @Override
        public boolean getReadLock(long timeout) {
            synchronized(lock) {
                boolean got_lock = false;
                long starttime = 0;
                if(timeout > 0)
                    starttime = System.currentTimeMillis();
                while(!got_lock) {
                    Integer lockcnt = filelocks.get(baseFilename);    /* Get lock count */
                    if(lockcnt == null) {
                        filelocks.put(baseFilename, Integer.valueOf(1));  /* First lock */
                        got_lock = true;
                    }
                    else if(!lockcnt.equals(WRITELOCK)) {   /* Other read locks */
                        filelocks.put(baseFilename, Integer.valueOf(lockcnt+1));
                        got_lock = true;
                    }
                    else {  /* Write lock in place */
                        try {
                            if(timeout < 0) {
                                lock.wait();
                            }
                            else {
                                long now = System.currentTimeMillis();
                                long elapsed = now-starttime; 
                                if(elapsed > timeout)   /* Give up on timeout */
                                    return false;
                                lock.wait(timeout-elapsed);
                            }
                        } catch (InterruptedException ix) {
                            Log.severe("getReadLock(" + baseFilename + ") interrupted");
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        @Override
        public void releaseReadLock() {
            synchronized(lock) {
                Integer lockcnt = filelocks.get(baseFilename);    /* Get lock count */
                if(lockcnt == null)
                    Log.severe("releaseReadLock(" + baseFilename + ") on unlocked file");
                else if(lockcnt.equals(WRITELOCK))
                    Log.severe("releaseReadLock(" + baseFilename + ") on write-locked file");
                else if(lockcnt > 1) {
                    filelocks.put(baseFilename, Integer.valueOf(lockcnt-1));
                }
                else {
                    filelocks.remove(baseFilename);   /* Remove lock */
                    lock.notifyAll();   /* Wake up folks waiting for locks */
                }
            }
        }

        @Override
        public void cleanup() {
        }
        
        @Override
        public String getURI() {
            return uri;
        }
        
        @Override
        public void enqueueZoomOutUpdate() {
            world.enqueueZoomOutUpdate(this);
        }
        @Override
        public MapStorageTile getZoomOutTile() {
            int xx, yy;
            int step = 1 << zoom;
            if(x >= 0)
                xx = x - (x % (2*step));
            else
                xx = x + (x % (2*step));
            yy = -y;
            if(yy >= 0)
                yy = yy - (yy % (2*step));
            else
                yy = yy + (yy % (2*step));
            yy = -yy;
            return new StorageTile(world, map, xx, yy, zoom+1, var);
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof StorageTile) {
                StorageTile st = (StorageTile) o;
                return baseFilename.equals(st.baseFilename);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return baseFilename.hashCode();
        }
        @Override
        public String toString() {
            return baseFilename;
        }
    }
    
    public FileTreeMapStorage() {
    }

    @Override
    public boolean init(DynmapCore core) {
        if (!super.init(core)) {
            return false;
        }
        baseTileDir = core.getTilesFolder();
        hashmap = new TileHashManager(baseTileDir, true);

        return true;
    }
    
    @Override
    public MapStorageTile getTile(DynmapWorld world, MapType map, int x, int y,
            int zoom, ImageVariant var) {
        return new StorageTile(world, map, x, y, zoom, var);
    }

    private void processEnumMapTiles(DynmapWorld world, MapType map, File base, ImageVariant var, MapStorageTileEnumCB cb) {
        File bdir = new File(base, map.getPrefix() + var.variantSuffix);
        if (bdir.isDirectory() == false) return;

        LinkedList<File> dirs = new LinkedList<File>(); // List to traverse
        dirs.add(bdir);   // Directory for map
        // While more paths to handle
        while (dirs.isEmpty() == false) {
            File dir = dirs.pop();
            String[] dirlst = dir.list();
            if (dirlst == null) continue;
            for(String fn : dirlst) {
                if (fn.equals(".") || fn.equals(".."))
                    continue;
                File f = new File(dir, fn);
                if (f.isDirectory()) {   /* If directory, add to list to process */
                    dirs.add(f);
                }
                else {  /* Else, file - see if tile */
                    String ext = null;
                    int extoff = fn.lastIndexOf('.');
                    if (extoff >= 0) {
                        ext = fn.substring(extoff+1);
                        fn = fn.substring(0, extoff);
                    }
                    if ((!ImageFormat.FORMAT_PNG.getFileExt().equalsIgnoreCase(ext)) && (!ImageFormat.FORMAT_JPG.getFileExt().equalsIgnoreCase(ext))) {
                        continue;
                    }
                    // See if zoom tile
                    int zoom = 0;
                    if (fn.startsWith("z")) {
                        while (fn.startsWith("z")) {
                            fn = fn.substring(1);
                            zoom++;
                        }
                        if (fn.startsWith("_")) {
                            fn = fn.substring(1);
                        }
                    }
                    // Split remainder to get coords
                    String[] coord = fn.split("_");
                    if (coord.length == 2) {    // Must be 2 to be a tile
                        try {
                            int x = Integer.parseInt(coord[0]);
                            int y = Integer.parseInt(coord[1]);
                            // Invoke callback
                            MapStorageTile t = new StorageTile(world, map, x, y, zoom, var);
                            cb.tileFound(t);
                            t.cleanup();
                        } catch (NumberFormatException nfx) {
                        }
                    }
                }
            }
        }
    }

    @Override
    public void enumMapTiles(DynmapWorld world, MapType map, MapStorageTileEnumCB cb) {
        File base = new File(baseTileDir, world.getName()); // Get base directory for world
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            for (ImageVariant var : ImageVariant.values()) {
                processEnumMapTiles(world, mt, base, var, cb);
            }
        }
    }

    private void processPurgeMapTiles(DynmapWorld world, MapType map, File base, ImageVariant var) {
        File bdir = new File(base, map.getPrefix() + var.variantSuffix);
        if (bdir.isDirectory() == false) return;

        LinkedList<File> dirs = new LinkedList<File>(); // List to traverse
        LinkedList<File> dirsdone = new LinkedList<File>(); // List to traverse
        dirs.add(bdir);   // Directory for map
        // While more paths to handle
        while (dirs.isEmpty() == false) {
            File dir = dirs.pop();
            dirsdone.add(dir);
            String[] dirlst = dir.list();
            if (dirlst == null) continue;
            for(String fn : dirlst) {
                if (fn.equals(".") || fn.equals(".."))
                    continue;
                File f = new File(dir, fn);
                if (f.isDirectory()) {   /* If directory, add to list to process */
                    dirs.add(f);
                }
                else {  /* Else, file - cleanup */
                    f.delete();
                }
            }
        }
        // Clean up directories, in reverse order of traverse
        int cnt = dirsdone.size();
        for (int i = cnt-1; i >= 0; i--) {
            dirsdone.get(i).delete();
        }
    }

    @Override
    public void purgeMapTiles(DynmapWorld world, MapType map) {
        File base = new File(baseTileDir, world.getName()); // Get base directory for world
        List<MapType> mtlist;

        if (map != null) {
            mtlist = Collections.singletonList(map);
        }
        else {  // Else, add all directories under world directory (for maps)
            mtlist = new ArrayList<MapType>(world.maps);
        }
        for (MapType mt : mtlist) {
            for (ImageVariant var : ImageVariant.values()) {
                processPurgeMapTiles(world, mt, base, var);
            }
        }
    }
}
