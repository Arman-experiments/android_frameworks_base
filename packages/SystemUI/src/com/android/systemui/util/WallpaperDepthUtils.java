/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.util;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.qs.QSImpl;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;

import java.lang.ref.WeakReference;

public class WallpaperDepthUtils {

    private static final String TAG = "WallpaperDepthUtils";
    private static final String WALLPAPER_DEPTH_KEY = "system:depth_wallpaper_subject_image_uri";
    private static final String WALLPAPER_DEPTH_ENABLED_KEY = "system:depth_wallpaper_enabled";
    private static final String WALLPAPER_DEPTH_OPACITY_KEY = "system:depth_wallpaper_opacity";
    private static final String WALLPAPER_DEPTH_OFFSET_X_KEY = "system:depth_wallpaper_offset_x";
    private static final String WALLPAPER_DEPTH_OFFSET_Y_KEY = "system:depth_wallpaper_offset_y";

    private static WallpaperDepthUtils instance;
    private FrameLayout mLockScreenSubject;
    private Drawable mDimmingOverlay;

    private final Context mContext;
    private final ConfigurationController mConfigurationController;
    private final KeyguardStateController mKeyguardStateController;
    private final ScrimController mScrimController;
    private final StatusBarStateController mStatusBarStateController;
    private final QSImpl mQS;
    private final TunerService mTunerService;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mDWallpaperEnabled;
    private int mDWallOpacity = 255;
    private String mWallpaperSubjectPath;
    private boolean mDozing;
    private boolean mWallpaperLoaded = false;
    private String mPreviousWallpaperPath;
    private Bitmap mWallpaperBitmap;
    private int mOffsetX;
    private int mOffsetY;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onThemeChanged() {
                    updateDepthWallpaper();
                }

                @Override
                public void onUiModeChanged() {
                    updateDepthWallpaper();
                }

                @Override
                public void onConfigChanged(Configuration newConfig) {
                    updateDepthWallpaper();
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardFadingAwayChanged() {
                    hideDepthWallpaper();
                }

                @Override
                public void onKeyguardGoingAwayChanged() {
                    hideDepthWallpaper();
                }
            };

    private WallpaperDepthUtils(Context context) {
        mContext = context;
        mQS = Dependency.get(QSImpl.class);
        mScrimController = Dependency.get(ScrimController.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mConfigurationController = Dependency.get(ConfigurationController.class);
        mKeyguardStateController = Dependency.get(KeyguardStateController.class);
        mTunerService = Dependency.get(TunerService.class);

        mTunerService.addTunable(mTunable, WALLPAPER_DEPTH_KEY,
                WALLPAPER_DEPTH_ENABLED_KEY, WALLPAPER_DEPTH_OPACITY_KEY,
                WALLPAPER_DEPTH_OFFSET_X_KEY, WALLPAPER_DEPTH_OFFSET_Y_KEY);

        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mConfigurationController.addCallback(mConfigurationListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);

        mLockScreenSubject = new FrameLayout(mContext) {
            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                WallpaperDepthUtils.this.onDetachedFromWindow();
            }
        };
        mLockScreenSubject.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public static WallpaperDepthUtils getInstance(Context context) {
        if (instance == null) {
            instance = new WallpaperDepthUtils(context);
        }
        return instance;
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                }

                @Override
                public void onDozingChanged(boolean dozing) {
                    if (mDozing == dozing) return;
                    mDozing = dozing;
                    updateDepthWallpaperVisibility();
                }
            };

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            switch (key) {
                case WALLPAPER_DEPTH_ENABLED_KEY:
                    mDWallpaperEnabled = TunerService.parseIntegerSwitch(newValue, false);
                    updateDepthWallpaper(true);
                    break;
                case WALLPAPER_DEPTH_KEY:
                    mPreviousWallpaperPath = mWallpaperSubjectPath;
                    mWallpaperSubjectPath = newValue;
                    updateDepthWallpaper(true);
                    break;
                case WALLPAPER_DEPTH_OPACITY_KEY:
                    mDWallOpacity = Math.round(TunerService.parseInteger(newValue, 100) * 2.55f);
                    updateDepthWallpaper(true);
                    break;
                case WALLPAPER_DEPTH_OFFSET_X_KEY:
                    mOffsetX = TunerService.parseInteger(newValue, 0);
                    updateDepthWallpaper(true);
                    break;
                case WALLPAPER_DEPTH_OFFSET_Y_KEY:
                    mOffsetY = TunerService.parseInteger(newValue, 0);
                    updateDepthWallpaper(true);
                    break;
            }
        }
    };

    public void updateDepthWallpaper() {
        updateDepthWallpaper(false);
    }

    public FrameLayout getDepthWallpaperView() {
        return mLockScreenSubject;
    }

    private boolean isDWallpaperEnabled() {
        return mDWallpaperEnabled && mWallpaperSubjectPath != null
                && !mWallpaperSubjectPath.isEmpty();
    }

    private boolean canShowDepthWallpaper() {
        return mLockScreenSubject != null && isDWallpaperEnabled()
                && mScrimController.getState().toString().equals("KEYGUARD")
                && mQS.isFullyCollapsed() && !mDozing
                && mContext.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;
    }

    public void updateDepthWallpaperVisibility() {
        if (mLockScreenSubject == null || !isDWallpaperEnabled()) return;
        int subjectVisibility = canShowDepthWallpaper() ? View.VISIBLE : View.GONE;
        if (mLockScreenSubject.getVisibility() == subjectVisibility) return;
        mLockScreenSubject.setVisibility(subjectVisibility);
    }

    public void hideDepthWallpaper() {
        if (mLockScreenSubject.getVisibility() == View.GONE) return;
        mLockScreenSubject.setVisibility(View.GONE);
    }

    private Bitmap getResizedBitmap(Bitmap wallpaperBitmap, float xOffsetDp, float yOffsetDp) {
        Rect displayBounds = mContext.getSystemService(WindowManager.class)
                .getCurrentWindowMetrics()
                .getBounds();
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        float xOffsetPx = xOffsetDp * displayMetrics.density;
        float yOffsetPx = yOffsetDp * displayMetrics.density;

        float ratioW = displayBounds.width() / (float) wallpaperBitmap.getWidth();
        float ratioH = displayBounds.height() / (float) wallpaperBitmap.getHeight();
        float scale = Math.max(ratioH, ratioW);

        int desiredWidth = Math.round(wallpaperBitmap.getWidth() * scale);
        int desiredHeight = Math.round(wallpaperBitmap.getHeight() * scale);

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(wallpaperBitmap, desiredWidth, desiredHeight, true);
        int xPixelShift = Math.max((desiredWidth - displayBounds.width()) / 2, 0) - Math.round(xOffsetPx);
        int yPixelShift = Math.max((desiredHeight - displayBounds.height()) / 2, 0) - Math.round(yOffsetPx);

        return Bitmap.createBitmap(scaledBitmap, Math.max(xPixelShift, 0), Math.max(yPixelShift, 0),
                Math.min(displayBounds.width(), scaledBitmap.getWidth() - xPixelShift),
                Math.min(displayBounds.height(), scaledBitmap.getHeight() - yPixelShift));
    }

    public void updateDepthWallpaper(boolean forced) {
        if (mLockScreenSubject == null || !isDWallpaperEnabled()) return;
        boolean pathChanged = (mPreviousWallpaperPath != null && !mPreviousWallpaperPath.equals(mWallpaperSubjectPath));
        if (!mWallpaperLoaded || pathChanged || forced) {
            Log.d(TAG, "Updating depth wallpaper");
            mHandler.post(new LoadWallpaperRunnable());
            mWallpaperLoaded = true;
            mPreviousWallpaperPath = mWallpaperSubjectPath;
        }
        updateDepthWallpaperVisibility();
    }

    private class LoadWallpaperRunnable implements Runnable {
        @Override
        public void run() {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(mWallpaperSubjectPath);
                if (bitmap == null) {
                    Log.d(TAG, "Failed to decode bitmap from file");
                    return;
                }
                Bitmap resizedBitmap = getResizedBitmap(bitmap, mOffsetX, mOffsetY);
                bitmap.recycle();

                if (resizedBitmap == null) {
                    Log.d(TAG, "Failed to resize bitmap");
                    return;
                }

                if (mWallpaperBitmap != null && !mWallpaperBitmap.isRecycled()) {
                    mWallpaperBitmap.recycle();
                }
                mWallpaperBitmap = resizedBitmap;

                Drawable bitmapDrawable = new BitmapDrawable(mContext.getResources(), mWallpaperBitmap);
                bitmapDrawable.setAlpha(255);
                mDimmingOverlay = bitmapDrawable.getConstantState().newDrawable().mutate();
                mDimmingOverlay.setTint(Color.BLACK);

                LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{bitmapDrawable, mDimmingOverlay});
                mLockScreenSubject.setBackground(layerDrawable);
                mLockScreenSubject.getBackground().setAlpha(mDWallOpacity);
                mDimmingOverlay.setAlpha(Math.round(mScrimController.getScrimBehindAlpha() * 240));
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Out of memory error", e);
            } catch (Exception e) {
                Log.e(TAG, "Error loading wallpaper", e);
            }
        }
    }

    public void onDetachedFromWindow() {
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);
        mTunerService.removeTunable(mTunable);

        if (mWallpaperBitmap != null && !mWallpaperBitmap.isRecycled()) {
            mWallpaperBitmap.recycle();
            mWallpaperBitmap = null;
        }
    }
}
