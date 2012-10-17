/*****************************************************************************
 * AudioUtil.java
 *****************************************************************************
 * Copyright © 2011-2012 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/
package org.videolan.vlc.gui.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.videolan.vlc.BitmapCache;
import org.videolan.vlc.Media;
import org.videolan.vlc.MurmurHash;
import org.videolan.vlc.R;
import org.videolan.vlc.Util;
import org.videolan.vlc.VLCApplication;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

public class AudioUtil {

    public final static String TAG = "VLC/AudioUtil";

    public static String CACHE_DIR = null;
    public static String COVER_DIR = null;

    public static void setRingtone( Media song, Activity activity){
        File newringtone = Util.URItoFile(song.getLocation());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, newringtone.getAbsolutePath());
        values.put(MediaStore.MediaColumns.TITLE, song.getTitle());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/*");
        values.put(MediaStore.Audio.Media.ARTIST, song.getArtist());
        values.put(MediaStore.Audio.Media.IS_RINGTONE, true);
        values.put(MediaStore.Audio.Media.IS_NOTIFICATION, false);
        values.put(MediaStore.Audio.Media.IS_ALARM, false);
        values.put(MediaStore.Audio.Media.IS_MUSIC, false);
        Uri uri = MediaStore.Audio.Media.getContentUriForPath(newringtone.getAbsolutePath());
        activity.getContentResolver().delete(uri, MediaStore.MediaColumns.DATA + "=\"" + newringtone.getAbsolutePath() + "\"", null);
        Uri newUri = activity.getContentResolver().insert(uri, values);
        RingtoneManager.setActualDefaultRingtoneUri(
                activity.getApplicationContext(),
                RingtoneManager.TYPE_RINGTONE,
                newUri
                );
    }

    @SuppressLint("NewApi")
    public static void prepareCacheFolder(Context context) {
        if (Util.isFroyoOrLater() && Util.hasExternalStorage() && context.getExternalCacheDir() != null)
            CACHE_DIR = context.getExternalCacheDir().getPath();
        else
            CACHE_DIR = Environment.getExternalStorageDirectory().getPath() + "/Android/data/" + context.getPackageName() + "/cache";
        COVER_DIR = CACHE_DIR + "/covers/";

        File file = new File(COVER_DIR);
        if (!file.exists())
            file.mkdirs();
    }

    private static String getCoverFromMediaStore(Context context, Media media) {
        ContentResolver contentResolver = context.getContentResolver();
        Uri uri = android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
        Cursor cursor = contentResolver.query(uri, new String[] {
                       MediaStore.Audio.Albums.ALBUM,
                       MediaStore.Audio.Albums.ALBUM_ART },
                       MediaStore.Audio.Albums.ALBUM + " LIKE ?",
                       new String[] { media.getAlbum() }, null);
        if (cursor == null) {
            // do nothing
        } else if (!cursor.moveToFirst()) {
            // do nothing
            cursor.close();
        } else {
            int titleColumn = cursor.getColumnIndex(android.provider.MediaStore.Audio.Albums.ALBUM_ART);
            String albumArt = cursor.getString(titleColumn);
            cursor.close();
            return albumArt;
        }
        return null;
    }

    private static String getCoverFromVlc(Context context, Media media) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        String artworkURL = media.getArtworkURL();
        if (artworkURL != null && artworkURL.startsWith("file://")) {
            return Uri.decode(artworkURL).replace("file://", "");
        } else if(artworkURL != null && artworkURL.startsWith("attachment://")) {
            // Decode if the album art is embedded in the file
            String mArtist = media.getArtist();
            String mAlbum = media.getAlbum();

            /* Parse decoded attachment */
            if( mArtist.length() == 0 || mAlbum.length() == 0 ||
                mArtist.equals(VLCApplication.getAppContext().getString(R.string.unknown_artist)) ||
                mAlbum.equals(VLCApplication.getAppContext().getString(R.string.unknown_album)) )
            {
                /* If artist or album are missing, it was cached by title MD5 hash */
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] binHash = md.digest((artworkURL + media.getTitle()).getBytes("UTF-8"));
                /* Convert binary hash to normal hash */
                BigInteger hash = new BigInteger(1, binHash);
                String titleHash = hash.toString(16);
                while(titleHash.length() < 32) {
                    titleHash = "0" + titleHash;
                }
                /* Use generated hash to find art */
                artworkURL = CACHE_DIR + "/art/arturl/" + titleHash + "/art.png";
            } else {
                /* Otherwise, it was cached by artist and album */
                artworkURL = CACHE_DIR + "/art/artistalbum/" + mArtist + "/" + mAlbum + "/art.png";
            }

            return artworkURL;
        }
        return null;
    }

    private static String getCoverFromFolder(Context context, Media media) {
        File f = Util.URItoFile(media.getLocation());
        for (File s : f.getParentFile().listFiles()) {
            if (s.getAbsolutePath().endsWith("png") ||
                    s.getAbsolutePath().endsWith("jpg"))
                return s.getAbsolutePath();
        }
        return null;
    }

    @SuppressLint("NewApi")
    public synchronized static Bitmap getCover(Context context, Media media, int width) {
        String coverPath = null;
        Bitmap cover = null;
        String cachePath = null;

        // if external storage is not available, skip covers to prevent slow audio browsing
        if (!Util.hasExternalStorage() && width > 0)
            return null;

        try {
            // try to load from cache
            int hash = MurmurHash.hash32(media.getArtist()+media.getAlbum());
            cachePath = COVER_DIR + (hash >= 0 ? "" + hash : "m" + (-hash)) + "_" + width;

            BitmapCache cache = BitmapCache.getInstance();
            // try to get the cover from the LRUCache first
            cover = cache.getBitmapFromMemCache(cachePath);
            if (cover != null)
                return cover;

            // try to get the cover from the storage cache
            File cacheFile = new File(cachePath);
            if (cacheFile != null && cacheFile.exists()) {
                if (cacheFile.length() > 0)
                    coverPath = cachePath;
                else
                    return null;
            }

            if (coverPath == null)
                coverPath = getCoverFromVlc(context, media);

            // no found yet, looking in folder
            if (coverPath == null)
                coverPath = getCoverFromFolder(context, media);

            // try to get the cover from android MediaStore
            if (coverPath == null)
                coverPath = getCoverFromMediaStore(context, media);

            // read (and scale?) the bitmap
            cover = readCoverBitmap(context, coverPath, width);

            // store cover into both cache
            writeBitmap(cover, cachePath);
            cache.addBitmapToMemCache(cachePath, cover);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return cover;
    }

    private static void writeBitmap(Bitmap bitmap, String path) throws IOException {
        OutputStream out = null;
        try {
            File file = new File(path);
            out = new BufferedOutputStream(new FileOutputStream(file), 4096);
            if (bitmap != null)
                bitmap.compress(CompressFormat.JPEG, 90, out);
        } catch (Exception e) {
            Log.e(TAG, "writeBitmap failed : "+ e.getMessage());
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private static Bitmap readCoverBitmap(Context context, String path, int width) {
        Bitmap cover = BitmapFactory.decodeFile(path);

        // scale down if requested
        if (cover != null && width > 0)
            cover = Util.scaleDownBitmap(context, cover, width);

        return cover;
    }
}
