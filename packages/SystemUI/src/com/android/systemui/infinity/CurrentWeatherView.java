/*
 * Copyright (C) 2023-2024 crDroid Android Project
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
package com.android.systemui.infinity;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Calendar;

import com.android.internal.util.infinity.OmniJawsClient;
import com.android.systemui.res.R;

public class CurrentWeatherView extends FrameLayout implements OmniJawsClient.OmniJawsObserver {

    private static final String TAG = "SystemUI:CurrentWeatherView";

    private ImageView mCurrentImage;
    private ImageView mWindInfoImage;
    private ImageView mPinwheelImage;
    private ImageView mHumidityInfoImage;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private TextView mLeftText;
    private TextView mRightText;
    private TextView mWeatherText;
    private TextView mWeatherWindSpeedInfo;
    private TextView mWeatherWindDirectionInfo;
    private TextView mWeatherHumidityInfo;

    private SettingsObserver mSettingsObserver;

    private boolean mShowWeatherLocation;
    private boolean mShowWeatherText;
    private boolean mShowWindInfo;
    private boolean mShowHumidityInfo;

    private Context mContext;

    public CurrentWeatherView(Context context) {
        this(context, null);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mWeatherClient = new OmniJawsClient(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCurrentImage = findViewById(R.id.current_image);
        mLeftText = findViewById(R.id.left_text);
        mRightText = findViewById(R.id.right_text);
        mWeatherText = findViewById(R.id.weather_text);
        mWindInfoImage = findViewById(R.id.wind_info_image);
        mWeatherWindSpeedInfo = findViewById(R.id.weather_wind_speed_info);
        mPinwheelImage = findViewById(R.id.pinwheel_image);
        mWeatherWindDirectionInfo = findViewById(R.id.weather_wind_direction_info);
        mHumidityInfoImage = findViewById(R.id.humidity_info_image);
        mWeatherHumidityInfo = findViewById(R.id.weather_humidity_info);

        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
            mSettingsObserver.observe();
        }
        
        queryAndUpdateWeather();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        enableUpdates();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        disableUpdates();
        if (mSettingsObserver != null) {
            mSettingsObserver.unobserve();
            mSettingsObserver = null;
        }
    }

    public void enableUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.addObserver(this);
            queryAndUpdateWeather();
        }
    }

    public void disableUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(this);
        }
    }

    private void setErrorView() {
        mCurrentImage.setImageDrawable(null);
        mLeftText.setText("");
        mRightText.setText("");
        mWeatherText.setText("");
        mWindInfoImage.setImageDrawable(null);
        mWeatherWindSpeedInfo.setText("");
        mPinwheelImage.setImageDrawable(null);
        mWeatherWindDirectionInfo.setText("");
        mHumidityInfoImage.setImageDrawable(null);
        mWeatherHumidityInfo.setText("");
    }

    @Override
    public void weatherError(int errorReason) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            setErrorView();
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void updateSettings() {
        queryAndUpdateWeather();
    }

    private void queryAndUpdateWeather() {
        if (mWeatherClient == null || !mWeatherClient.isOmniJawsEnabled()) {
            setErrorView();
            return;
        }

        try {
            mWeatherClient.queryWeather();
            mWeatherInfo = mWeatherClient.getWeatherInfo();

            if (mWeatherInfo != null) {
                updateWeatherViews();
            } else {
            	setErrorView();
            } 	
        } catch (Exception e) {
            Log.e(TAG, "queryWeather", e);
            setErrorView();
        }
    }

    private void updateWeatherViews() {
        String formattedCondition = getFormattedCondition(mWeatherInfo.condition);
        Drawable conditionDrawable = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);

        mCurrentImage.setImageDrawable(conditionDrawable);
        mRightText.setText(formatTemperature(mWeatherInfo.temp, mWeatherInfo.tempUnits));
        mLeftText.setText(mWeatherInfo.city);
        mLeftText.setVisibility(mShowWeatherLocation ? View.VISIBLE : View.GONE);
        mWeatherText.setText(" · " + formattedCondition);
        mWeatherText.setVisibility(mShowWeatherText ? View.VISIBLE : View.GONE);

        updateWindInfo();
        updateHumidityInfo();
    }

    private String getFormattedCondition(String condition) {
        if (condition == null) return "";

        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String lowerCondition = condition.toLowerCase();
        if (lowerCondition.contains("clouds")) {
            return mContext.getString(R.string.weather_condition_clouds);
        } else if (lowerCondition.contains("rain")) {
            return mContext.getString(R.string.weather_condition_rain);
        } else if (lowerCondition.contains("clear")) {
            if (hourOfDay >= 7 && hourOfDay < 18) {
            return mContext.getString(R.string.weather_condition_clear);
            } else {
                     return mContext.getString(R.string.weather_condition_clear_evening);
            }
        } else if (lowerCondition.contains("storm")) {
            return mContext.getString(R.string.weather_condition_storm);
        } else if (lowerCondition.contains("snow")) {
            return mContext.getString(R.string.weather_condition_snow);
        } else if (lowerCondition.contains("wind")) {
            return mContext.getString(R.string.weather_condition_wind);
        } else if (lowerCondition.contains("mist")) {
            return mContext.getString(R.string.weather_condition_mist);
        }
        return condition;
    }

    private String formatTemperature(String temp, String units) {
        return new StringBuilder().append(temp).append(" ").append(units).toString();
    }

    private void updateWindInfo() {
        Drawable windImage = mWeatherClient.getResOmni("ic_wind_symbol");
        Drawable pinWheel = mWeatherClient.getResOmni("ic_wind_direction_symbol");

        mWindInfoImage.setImageDrawable(windImage);
        mPinwheelImage.setImageDrawable(pinWheel);
        mWeatherWindSpeedInfo.setText(formatWindSpeed(mWeatherInfo.windSpeed, mWeatherInfo.windUnits));
        mWeatherWindDirectionInfo.setText(formatWindDirection(mWeatherInfo.pinWheel));

        int windVisibility = mShowWindInfo ? View.VISIBLE : View.GONE;
        mWindInfoImage.setVisibility(windVisibility);
        mPinwheelImage.setVisibility(windVisibility);
        mWeatherWindSpeedInfo.setVisibility(windVisibility);
        mWeatherWindDirectionInfo.setVisibility(windVisibility);
    }

    private String formatWindSpeed(String speed, String units) {
        return new StringBuilder().append(speed).append(" ").append(units).toString();
    }

    private String formatWindDirection(String direction) {
        return new StringBuilder().append(direction).toString();
    }

    private void updateHumidityInfo() {
        Drawable humidityImage = mWeatherClient.getResOmni("ic_humidity_symbol");
        mHumidityInfoImage.setImageDrawable(humidityImage);
        mWeatherHumidityInfo.setText(mWeatherInfo.humidity);

        int humidityVisibility = mShowHumidityInfo ? View.VISIBLE : View.GONE;
        mHumidityInfoImage.setVisibility(humidityVisibility);
        mWeatherHumidityInfo.setVisibility(humidityVisibility);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_WEATHER_LOCATION),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_WEATHER_TEXT),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_WEATHER_WIND_INFO),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_WEATHER_HUMIDITY_INFO),
                    false, this, UserHandle.USER_ALL);
            updateWeatherSettings();
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        void updateWeatherSettings() {
            mShowWeatherLocation = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_LOCATION,
                    0, UserHandle.USER_CURRENT) != 0;
            mShowWeatherText = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_TEXT,
                    1, UserHandle.USER_CURRENT) != 0;
            mShowWindInfo = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_WIND_INFO,
                    1, UserHandle.USER_CURRENT) != 0;
            mShowHumidityInfo = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_HUMIDITY_INFO,
                    1, UserHandle.USER_CURRENT) != 0;

            queryAndUpdateWeather();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateWeatherSettings();
        }
    }
}
