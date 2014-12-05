/*
 * Copyright (C) 2014 Fastboot Mobile, LLC.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program;
 * if not, see <http://www.gnu.org/licenses>.
 */

package org.omnirom.music.framework;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.omnirom.music.model.BoundEntity;
import org.omnirom.music.model.Playlist;
import org.omnirom.music.model.Song;
import org.omnirom.music.providers.IArtCallback;
import org.omnirom.music.providers.ProviderAggregator;

import java.util.ArrayList;
import java.util.List;

/**
 * Class creating a composite image for playlist cover art
 */
public class PlaylistArtBuilder {
    private static final String TAG = "PlaylistArtBuilder";

    private Bitmap mPlaylistComposite;
    private List<RecyclingBitmapDrawable> mPlaylistSource;
    private Paint mPlaylistPaint;
    private List<AlbumArtHelper.AlbumArtTask> mCompositeTasks;
    private List<BoundEntity> mCompositeRequests;
    private int mNumComposite;
    private Handler mHandler;
    private IArtCallback mCallback;
    private boolean mDone;

    private Runnable mTimeoutWatchdog = new Runnable() {
        @Override
        public void run() {
            Log.e(TAG, "Watchdog kicking " + mNumComposite + " images");

            if (mPlaylistComposite != null) {
                try {
                    mCallback.onArtLoaded(mPlaylistComposite.copy(Bitmap.Config.ARGB_8888, false));
                } catch (RemoteException ignored) {
                }
            }

            mDone = true;
        }
    };

    private Runnable mUpdatePlaylistCompositeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPlaylistComposite == null || !mPlaylistComposite.isRecycled() && !mDone) {
                makePlaylistComposite();
            }
        }
    };

    private AlbumArtHelper.AlbumArtListener mCompositeListener = new AlbumArtHelper.AlbumArtListener() {
        @Override
        public void onArtLoaded(RecyclingBitmapDrawable output, BoundEntity request) {
            if (!mCompositeRequests.contains(request) || mPlaylistSource == null || mDone) {
                return;
            }

            if (output != null) {
                mPlaylistSource.add(output);
                if (mPlaylistSource.size() < 4) {
                    mHandler.removeCallbacks(mUpdatePlaylistCompositeRunnable);
                    mHandler.postDelayed(mUpdatePlaylistCompositeRunnable, 200);
                } else {
                    mHandler.removeCallbacks(mUpdatePlaylistCompositeRunnable);
                    mHandler.post(mUpdatePlaylistCompositeRunnable);
                }
            }
        }
    };

    public PlaylistArtBuilder() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void freeMemory() {
        if (mPlaylistComposite != null) {
            mPlaylistComposite.recycle();
            mPlaylistComposite = null;
        }
        if (mPlaylistSource != null) {
            mPlaylistSource.clear();
            mPlaylistSource = null;
        }
        if (mCompositeTasks != null) {
            for (AlbumArtHelper.AlbumArtTask task : mCompositeTasks) {
                task.cancel(true);
            }
            mCompositeTasks.clear();
            mCompositeTasks = null;
        }
    }


    private synchronized void makePlaylistComposite() {
        if (mPlaylistComposite == null) {
            mPlaylistComposite = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
        }

        if (mPlaylistPaint == null) {
            mPlaylistPaint = new Paint();
        }

        Canvas canvas = new Canvas(mPlaylistComposite);
        final int numImages = mPlaylistSource.size();
        final int compositeWidth = mPlaylistComposite.getWidth();
        final int compositeHeight = mPlaylistComposite.getHeight();

        if (numImages == 1) {
            // If we expected only one image, return this result
            if (mNumComposite == 1) {
                mDone = true;
                mHandler.removeCallbacks(mTimeoutWatchdog);
                try {
                    mCallback.onArtLoaded(mPlaylistSource.get(0).getBitmap());
                } catch (RemoteException ignored) {
                }
                return;
            }
        } else if (numImages == 2 || numImages == 3) {
            int i = 0;
            for (RecyclingBitmapDrawable item : mPlaylistSource) {
                Bitmap itemBmp = item.getBitmap();

                Rect src = new Rect(0, 0, itemBmp.getWidth(), itemBmp.getHeight());
                Rect dst = new Rect(i * compositeWidth / numImages,
                        0,
                        i * compositeWidth / numImages + compositeWidth,
                        compositeHeight);

                canvas.drawBitmap(itemBmp, src, dst, mPlaylistPaint);
                ++i;
            }
        } else {
            for (int i = 0; i < 4; ++i) {
                RecyclingBitmapDrawable item = mPlaylistSource.get(i);
                Bitmap itemBmp = item.getBitmap();

                int row = (int) Math.floor(i / 2);
                int col = (i % 2);

                Rect src = new Rect(0, 0, itemBmp.getWidth(), itemBmp.getHeight());
                Rect dst = new Rect(col * compositeWidth / 2,
                        row * compositeHeight / 2,
                        col * compositeWidth / 2 + compositeWidth / 2,
                        row * compositeHeight / 2 + compositeHeight / 2);

                canvas.drawBitmap(itemBmp, src, dst, mPlaylistPaint);
            }
        }

        Log.e(TAG, "Got image " + numImages + "/" + mNumComposite);

        if (numImages == mNumComposite) {
            mDone = true;
            mHandler.removeCallbacks(mTimeoutWatchdog);
            try {
                mCallback.onArtLoaded(mPlaylistComposite.copy(Bitmap.Config.ARGB_8888, false));
            } catch (RemoteException ignored) {
            }
        }
    }

    public void start(Resources res, Playlist playlist, IArtCallback callback) {
        Log.e(TAG, "Starting to build playlist art for " + playlist.getName());

        mDone = false;
        mCallback = callback;

        if (mCompositeTasks == null) {
            mCompositeTasks = new ArrayList<>();
        } else {
            // Cancel the current tasks
            for (AlbumArtHelper.AlbumArtTask task : mCompositeTasks) {
                task.cancel(true);
            }
            mCompositeTasks.clear();
        }

        // Load 4 songs if possible and compose them into one picture
        mPlaylistSource = new ArrayList<>();
        mCompositeRequests = new ArrayList<>();
        mNumComposite = Math.min(4, playlist.getSongsCount());
        final ProviderAggregator aggregator = ProviderAggregator.getDefault();

        for (int i = 0; i < mNumComposite; ++i) {
            String entry = playlist.songsList().get(i);
            Song song = aggregator.retrieveSong(entry, playlist.getProvider());
            mCompositeTasks.add(AlbumArtHelper.retrieveAlbumArt(res, mCompositeListener, song, false));
            mCompositeRequests.add(song);
        }

        mHandler.postDelayed(mTimeoutWatchdog, 5000);
    }
}
