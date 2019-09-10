package com.example.cachingbitmaps.java;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.cachingbitmaps.BuildConfig;
import com.example.cachingbitmaps.R;
import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity_Java_Disk_Cache extends AppCompatActivity {

    private LruCache<String, Bitmap> memoryCache;

    private DiskLruCache diskLruCache;
    private final Object diskCacheLock = new Object();
    private boolean diskCacheStarting = true;
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB
    private static final String DISK_CACHE_SUBDIR = "thumbnails";

    private static final int APP_VERSION = 1;
    private static final int VALUE_COUNT = 1;
    private Bitmap.CompressFormat mCompressFormat = Bitmap.CompressFormat.JPEG;
    private int mCompressQuality = 70;
    private static final int IO_BUFFER_SIZE = 4 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ** Initialize memory cache
        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = maxMemory / 8;

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.getByteCount() / 1024;
            }
        };

        // ** Initialize disk cache on background thread
        File cacheDir = getDiskCacheDir(this, DISK_CACHE_SUBDIR);
        new InitDiskCacheTask().execute(cacheDir);
    }

    // Creates a unique subdirectory of the designated app cache directory. Tries to use external
    // but if not mounted, falls back on internal storage.
    public File getDiskCacheDir(Context context, String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external cache dir
        // otherwise use internal cache dir
        final String cachePath =
                Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) ||
                        !Environment.isExternalStorageRemovable() ? getExternalCacheDir().getPath() :
                        context.getCacheDir().getPath();

        return new File(cachePath + File.separator + uniqueName);
    }

    public Bitmap getBitmapFromMemCache(String key) {
        return memoryCache.get(key);
    }

    public void loadBitmap(int resId, ImageView imageView) {
        final String imageKey = String.valueOf(resId);

        final Bitmap bitmap = getBitmapFromMemCache(imageKey);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        } else {
            imageView.setImageResource(R.drawable.ic_launcher_background);
            BitmapWorkerTask task = new BitmapWorkerTask(imageView);
            task.execute(resId);
        }
    }


    class InitDiskCacheTask extends AsyncTask<File, Void, Void> {

        @Override
        protected Void doInBackground(File... params) {
            synchronized (diskCacheLock) {
                File cacheDir = params[0];
                try {
                    diskLruCache = DiskLruCache.open(cacheDir, APP_VERSION, VALUE_COUNT, DISK_CACHE_SIZE);
                } catch (IOException e) {

                }
                diskCacheStarting = false; // Finished initialization
                diskCacheLock.notifyAll(); // Wake any waiting threads
            }
            return null;
        }
    }

    class BitmapWorkerTask extends AsyncTask<Integer, Void, Bitmap> {

        private ImageView imageView;

        public BitmapWorkerTask(ImageView imageView) {
            this.imageView = imageView;
        }

        // Decode image in background.
        @Override
        protected Bitmap doInBackground(Integer... params) {
            final String imageKey = String.valueOf(params[0]);

            // Check disk cache in background thread
            Bitmap bitmap = getBitmapFromDiskCache(imageKey);

            if (bitmap == null) { // Not found in disk cache
                // Process as normal
                bitmap = decodeSampledBitmapFromResource(getResources(), params[0], 100, 100);
            }

            // Add final bitmap to caches
            addBitmapToCache(imageKey, bitmap);

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            imageView.setImageBitmap(bitmap);
        }

        public Bitmap decodeSampledBitmapFromResource(Resources res, int resId, int reqWidth, int reqHeight) {
            // First decode with inJustDecodeBounds=true to check dimensions
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(res, resId, options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeResource(res, resId, options);
        }

        public int calculateInSampleSize(
                BitmapFactory.Options options, int reqWidth, int reqHeight) {
            // Raw height and width of image
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;

            if (height > reqHeight || width > reqWidth) {

                final int halfHeight = height / 2;
                final int halfWidth = width / 2;

                // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                // height and width larger than the requested height and width.
                while ((halfHeight / inSampleSize) >= reqHeight
                        && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }

            return inSampleSize;
        }

        public void addBitmapToCache(String key, Bitmap bitmap) {
            // Add to memory cache as before
            if (getBitmapFromMemCache(key) == null) {
                memoryCache.put(key, bitmap);
            }

            // Also add to disk cache
            synchronized (diskCacheLock) {
                try {
                    if (diskLruCache != null && diskLruCache.get(key) == null) {
                        put(key, bitmap);
                    }
                } catch (IOException e) {

                }
            }
        }

        public void put( String key, Bitmap data ) {

            DiskLruCache.Editor editor = null;
            try {
                editor = diskLruCache.edit( key );
                if ( editor == null ) {
                    return;
                }

                if( writeBitmapToFile( data, editor ) ) {
                    diskLruCache.flush();
                    editor.commit();
                    if ( BuildConfig.DEBUG ) {
                        Log.d( "cache_test_DISK_", "image put on disk cache " + key );
                    }
                } else {
                    editor.abort();
                    if ( BuildConfig.DEBUG ) {
                        Log.d( "cache_test_DISK_", "ERROR on: image put on disk cache " + key );
                    }
                }
            } catch (IOException e) {
                if ( BuildConfig.DEBUG ) {
                    Log.d( "cache_test_DISK_", "ERROR on: image put on disk cache " + key );
                }
                try {
                    if ( editor != null ) {
                        editor.abort();
                    }
                } catch (IOException ignored) {
                }
            }
        }

        private boolean writeBitmapToFile( Bitmap bitmap, DiskLruCache.Editor editor )
                throws IOException {
            OutputStream out = null;
            try {
                out = new BufferedOutputStream( editor.newOutputStream( 0 ), IO_BUFFER_SIZE );
                return bitmap.compress( mCompressFormat, mCompressQuality, out );
            } finally {
                if ( out != null ) {
                    out.close();
                }
            }
        }

        public Bitmap getBitmapFromDiskCache(String key) {
            synchronized (diskCacheLock) {
                // Wait while disk cache is started from background thread
                while (diskCacheStarting) {
                    try {
                        diskCacheLock.wait();
                    } catch (InterruptedException e) {}
                }

                if (diskLruCache != null) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeStream(diskLruCache.get(key).getInputStream(0));
                        return bitmap;
                    } catch (IOException e) {

                    }
                }
            }
            return null;
        }
    }

}
