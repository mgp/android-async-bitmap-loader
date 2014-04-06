package com.omgitsmgp.asyncbitmaploader;

import java.lang.ref.WeakReference;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.support.v4.util.LruCache;
import android.widget.ImageView;

/**
 * A class that helps load {@link Bitmap} instances asynchronously. This class combines the
 * implementations described in
 * http://developer.android.com/training/displaying-bitmaps/process-bitmap.html and
 * http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html.
 * 
 * To use, define a subclass that implements abstract method {@link #loadBitmap(Object)}. Then
 * instantiate that subclass and call its {@link #loadBitmap(Object, ImageView)} method. If the
 * bitmap with the given key is cached, then it is immediately set as the content of the image view.
 * Otherwise, method {@link #loadBitmap(Object)} is called asynchronously to load the bitmap. It is
 * then cached and assigned as the content of the image view.
 * 
 * @param <K> The type of the key for each bitmap. Instances of this type should be immutable.
 * 
 * @author Michael G. Parker (http://omgitsmgp.com)
 */
public abstract class AsyncBitmapLoader<K> {
	private final Resources resources;
	private final Bitmap placeholderBitmap;
	private final LruCache<K, Bitmap> bitmapCache;

	private static final int getCacheDefaultSize() {
		int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
		return maxMemory / 8;
	}
	
	/**
	 * Constructs a new instance that uses the given placeholder bitmap for asynchronously loading
	 * bitmaps, and has a cache with a maximum size of 1/8 of the available memory.
	 * 
	 * @param resources a {@link Resources} instance for the application's package
	 * @param placeholderBitmapResId the resource identifier of the placeholder bitmap
	 */
	public AsyncBitmapLoader(Resources resources, int placeholderBitmapResId) {
		this(resources, placeholderBitmapResId, getCacheDefaultSize());
	}

	/**
	 * Constructs a new instance that uses the given placeholder bitmap for asynchronously loading
	 * bitmaps, and has a cache with the given maximum size.
	 * 
	 * @param resources a {@link Resources} instance for the application's package
	 * @param placeholderBitmapResId the resource identifier of the placeholder bitmap
	 * @param maxCacheSize the maximum size of the cache
	 */
	public AsyncBitmapLoader(Resources resources, int placeholderBitmapResId, int maxCacheSize) {
		this.resources = resources;
		placeholderBitmap = BitmapFactory.decodeResource(resources, placeholderBitmapResId);
		bitmapCache = new LruCache<K, Bitmap>(maxCacheSize) {
			@Override
			protected int sizeOf(K key, Bitmap bitmap) {
				// From http://stackoverflow.com/a/15711992/400717.
				return bitmap.getRowBytes() * bitmap.getHeight();
			}
		};
	}

	/**
	 * @return the {@link Bitmap} instance associated with the given key, possibly {@code null}
	 */
	protected abstract Bitmap loadBitmap(K key);
	
	/**
	 * Sets the {@link Bitmap} instance associated with the given key as the content of the given
	 * {@link ImageView} instance.
	 * 
	 * If this bitmap is cached, then it is set as the content of the image view immediately, and this
	 * method returns {@code true}.
	 * 
	 * If the bitmap is not cached, then the placeholder bitmap is set as the content of the image
	 * view immediately. The bitmap is asynchronously loaded by invoking {@link #loadBitmap(Object)}
	 * before it is assigned as the content of the image view. In this case, this method returns
	 * {@code false}.
	 * 
	 * @param key the key of the bitmap
	 * @param imageView the image view
	 * @return {@code true} if a cached bitmap was assigned as the content of the given image view,
	 *         {@code false} if the bitmap will be asynchronously loaded and assigned  
	 */
	@SuppressWarnings("unchecked")
	public final boolean loadBitmap(K key, ImageView imageView) {
		Bitmap bitmap = getBitmapFromCache(key);
		if (bitmap != null) {
			imageView.setImageBitmap(bitmap);
			return true;
		} else if (cancelTaskForOtherKey(key, imageView)) {
			final BitmapWorkerTask<K> task = new BitmapWorkerTask<K>(this, imageView);
			final AsyncDrawable<K> asyncDrawable = new AsyncDrawable<K>(
					resources, placeholderBitmap, task);
			imageView.setImageDrawable(asyncDrawable);
			task.execute(key);
		}
		return false;
	}

	/**
	 * Adds the given {@link Bitmap} instance to the cache.
	 */
	private void addBitmapToCache(K key, Bitmap bitmap) {
		synchronized (bitmapCache) {
			if (getBitmapFromCache(key) == null) {
				bitmapCache.put(key, bitmap);
			}
		}
	}

	/**
	 * @return the {@link Bitmap} with the given key from the cache, or {@code null} if missing
	 */
	private Bitmap getBitmapFromCache(K key) {
		return bitmapCache.get(key);
	}

	/**
	 * An {@link AsyncTask} that asynchronously loads a bitmap and assigns it as the content of an
	 * {@link ImageView} instance.
	 */
	private static final class BitmapWorkerTask<K> extends AsyncTask<K, Void, Bitmap> {
		private final AsyncBitmapLoader<K> bitmapLoader;
		private final WeakReference<ImageView> imageViewReference;
		
		private K key;

		public BitmapWorkerTask(AsyncBitmapLoader<K> bitmapLoader, ImageView imageView) {
			// Pass in the AsyncBitmapLoader instance instead of making this class non-static. This allows
			// class AsyncDrawable can remain static.
			this.bitmapLoader = bitmapLoader;
			// Use a WeakReference to ensure the ImageView can be garbage collected.
			imageViewReference = new WeakReference<ImageView>(imageView);
		}
		
		@Override
		protected Bitmap doInBackground(K... args) {
			key = args[0];
			Bitmap bitmap = bitmapLoader.loadBitmap(key);
			bitmapLoader.addBitmapToCache(key, bitmap);
			return bitmap;
		}
		
		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (isCancelled()) {
				bitmap = null;
			} else if ((imageViewReference != null) && (bitmap != null)) {
				final ImageView imageView = imageViewReference.get();
				final BitmapWorkerTask<K> bitmapWorkerTask = getBitmapWorkerTask(imageView);
				if ((this == bitmapWorkerTask) && (imageView != null)) {
					imageView.setImageBitmap(bitmap);
				}
			}
		}
	}
	
	/**
	 * A {@link BitmapDrawable} subclass that refers to the {@link BitmapWorkerTask} that populates
	 * it.
	 */
	private static final class AsyncDrawable<K> extends BitmapDrawable {
		private final WeakReference<BitmapWorkerTask<K>> bitmapWorkerTaskReference;
		
		public AsyncDrawable(Resources resources, Bitmap bitmap, BitmapWorkerTask<K> bitmapWorkerTask) {
			super(resources, bitmap);
			bitmapWorkerTaskReference = new WeakReference<BitmapWorkerTask<K>>(bitmapWorkerTask);
		}
		
		public BitmapWorkerTask<K> getBitmapWorkerTask() {
			return bitmapWorkerTaskReference.get();
		}
	}
	
	/**
	 * Cancels any {@link BitmapWorkerTask} associated with the given {@link ImageView} if it is
	 * loading a bitmap with a key not equal to the given key.
	 * 
	 * If the image view has no associated task, this method returns {@code true}. Alternatively, if
	 * it has a task with a key not equal to the current key, then that task is cancelled and this
	 * method returns {@code true}. The caller can proceed to assign a new task to the image view that
	 * loads the image with the given key.
	 * 
	 * If the image view a task with a key equal to the current key, then this method returns
	 * {@code false}. The caller does not need to take action, because the image view is already
	 * loading the bitmap with the given key.
	 * 
	 * @param key the key of the bitmap
	 * @param imageView the image view
	 * @return {@code true} if a task with a differing key was cancelled or no task was found,
	 *         {@code false} if a task with the given key was found
	 */
	private static <K> boolean cancelTaskForOtherKey(K key, ImageView imageView) {
		final BitmapWorkerTask<K> bitmapWorkerTask = getBitmapWorkerTask(imageView);

		if (bitmapWorkerTask != null) {
			final K taskKey = bitmapWorkerTask.key;
			// Test whether taskKey is not yet set or it differs from the given photo identifier.
			if ((taskKey == null) || !taskKey.equals(key)) {
				// Differs, so cancel execution of this task.
				bitmapWorkerTask.cancel(true);
			} else {
				// Loading the bitmap with the given key is already in progress.
				return false;
			}
		}
		// No task associated with the ImageView, or an existing task was cancelled.
		return true;
	}

	/**
	 * @return the {@link BitmapWorkerTask} that populates the given {@link ImageView}
	 */
	private static <K> BitmapWorkerTask<K> getBitmapWorkerTask(ImageView imageView) {
		if (imageView != null) {
			final Drawable drawable = imageView.getDrawable();
			if (drawable instanceof AsyncDrawable) {
				@SuppressWarnings("unchecked")
				final AsyncDrawable<K> asyncDrawable = (AsyncDrawable<K>) drawable;
				return asyncDrawable.getBitmapWorkerTask();
			}
		}
		return null;
	}
}
