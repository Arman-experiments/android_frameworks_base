/*
 * Copyright (C) 2018 crDroid Android Project
 * Copyright (C) 2018-2019 AICP
 * Copyright (C) 2023 Superior-Extended
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

package com.android.systemui.infinity.logo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public abstract class LogoImage extends ImageView implements DarkReceiver {

    private WeakReference<Context> mContextRef;
    private boolean mAttached;
    private boolean mShowLogo;
    private int mLogoPosition;
    private int mLogoStyle;
    private int mTintColor = Color.WHITE;
    private int mLogoColor;
    private int mLogoColorCustom;
    private int mAccentColor;

    private static final String STATUS_BAR_LOGO = Settings.System.STATUS_BAR_LOGO;
    private static final String STATUS_BAR_LOGO_POSITION = Settings.System.STATUS_BAR_LOGO_POSITION;
    private static final String STATUS_BAR_LOGO_STYLE = Settings.System.STATUS_BAR_LOGO_STYLE;
    private static final String STATUS_BAR_LOGO_COLOR = Settings.System.STATUS_BAR_LOGO_COLOR;
    private static final String STATUS_BAR_LOGO_COLOR_PICKER = Settings.System.STATUS_BAR_LOGO_COLOR_PICKER;

    private static final Map<Integer, Drawable> sDrawableCache = new HashMap<>();

    private SettingsObserver observer;

    public LogoImage(Context context) {
        this(context, null);
    }

    public LogoImage(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogoImage(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContextRef = new WeakReference<>(context);
        init();
    }

    private void init() {
        mAttached = false;
        observer = new SettingsObserver(null);
        observer.observe();
        updateSettings();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            ContentResolver resolver = getContext().getContentResolver();
            resolver.unregisterContentObserver(observer);
            Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(areas, this, tint);
        if (mShowLogo && !isLogoHidden()) {
            updateLogo();
        }
    }

    public void updateLogo() {
        Drawable drawable = getLogoDrawable(mLogoStyle);
        if (drawable == null) return;

        setImageDrawable(null);
        clearColorFilter();

        if (mLogoColor == 0) {
            drawable.setTint(mTintColor);
        } else if (mLogoColor == 1) {
            mAccentColor = getContext().getColor(com.android.internal.R.color.logo_accent_color);
            setColorFilter(mAccentColor, PorterDuff.Mode.SRC_IN);
        } else if (mLogoColor == 2) {
            setColorFilter(mLogoColorCustom, PorterDuff.Mode.SRC_IN);
        }
        setImageDrawable(drawable);
    }

    private Drawable getLogoDrawable(int style) {
        Context context = mContextRef.get();
        if (context == null) return null;

        if (sDrawableCache.containsKey(style)) {
            return sDrawableCache.get(style);
        }

        int[] logoResources = {
            R.drawable.ic_infinity_logo, R.drawable.ic_adidas, R.drawable.ic_airjordan, 
            R.drawable.ic_alien, R.drawable.ic_amogus, R.drawable.ic_apple_logo, 
            R.drawable.ic_avengers, R.drawable.ic_batman, R.drawable.ic_batman_tdk, 
            R.drawable.ic_beats, R.drawable.ic_biohazard, R.drawable.ic_blackberry, 
            R.drawable.ic_cannabis, R.drawable.ic_captain_america, R.drawable.ic_fire, 
            R.drawable.ic_flash, R.drawable.ic_ghost, R.drawable.ic_heart, 
            R.drawable.ic_ironman, R.drawable.ic_mint_logo, R.drawable.ic_nike, 
            R.drawable.ic_ninja, R.drawable.ic_pac_man, R.drawable.ic_puma, 
            R.drawable.ic_robot, R.drawable.ic_rog, R.drawable.ic_spiderman, 
            R.drawable.ic_superman, R.drawable.ic_tux_logo, R.drawable.ic_ubuntu_logo, 
            R.drawable.ic_windows, R.drawable.ic_xbox
        };

        int index = (style >= 0 && style < logoResources.length) ? style : 0;
        Drawable drawable = context.getDrawable(logoResources[index]);
        sDrawableCache.put(style, drawable);

        return drawable;
    }
    
    protected int getLogoPosition() {
         return mLogoPosition;
    }

    public void updateSettings() {
        Context context = mContextRef.get();
        if (context == null) return;

        mShowLogo = Settings.System.getInt(context.getContentResolver(), STATUS_BAR_LOGO, 0) != 0;
        mLogoPosition = Settings.System.getInt(context.getContentResolver(), STATUS_BAR_LOGO_POSITION, 0);
        mLogoStyle = Settings.System.getInt(context.getContentResolver(), STATUS_BAR_LOGO_STYLE, 0);
        mLogoColor = Settings.System.getInt(context.getContentResolver(), STATUS_BAR_LOGO_COLOR, 0);
        mLogoColorCustom = Settings.System.getInt(context.getContentResolver(), STATUS_BAR_LOGO_COLOR_PICKER, 0xff1a73e8);

        if (!mShowLogo || isLogoHidden()) {
            setImageDrawable(null);
            setVisibility(View.GONE);
            return;
        }
        updateLogo();
        setVisibility(View.VISIBLE);
    }

    protected abstract boolean isLogoHidden();

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(STATUS_BAR_LOGO), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(STATUS_BAR_LOGO_POSITION), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(STATUS_BAR_LOGO_STYLE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(STATUS_BAR_LOGO_COLOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(STATUS_BAR_LOGO_COLOR_PICKER), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            updateSettings();
        }
    }
}
