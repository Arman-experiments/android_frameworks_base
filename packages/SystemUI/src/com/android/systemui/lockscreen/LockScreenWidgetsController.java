/*
     Copyright (C) 2024 the risingOS Android Project
     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at
          http://www.apache.org/licenses/LICENSE-2.0
     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
*/
package com.android.systemui.lockscreen;

import android.annotation.NonNull;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.AttributeSet;
import android.os.UserHandle;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.settingslib.net.DataUsageController;
import com.android.settingslib.Utils;

import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.animation.Expandable;
import com.android.systemui.animation.view.LaunchableImageView;
import com.android.systemui.animation.view.LaunchableFAB;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogViewModel;
import com.android.systemui.qs.tiles.dialog.InternetDialogManager;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
import com.android.systemui.util.MediaSessionManagerHelper;
import com.android.internal.util.android.VibrationUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.android.internal.util.infinity.OmniJawsClient;

public class LockScreenWidgetsController implements OmniJawsClient.OmniJawsObserver, MediaSessionManagerHelper.MediaMetadataListener {

    private static final String LOCKSCREEN_WIDGETS_ENABLED = "lockscreen_widgets_enabled";
    private static final String LOCKSCREEN_WIDGETS = "lockscreen_widgets";
    private static final String LOCKSCREEN_WIDGETS_EXTRAS = "lockscreen_widgets_extras";
    private static final String LOCKSCREEN_WIDGETS_STYLE = "lockscreen_widgets_style";
    private static final String LOCKSCREEN_WIDGETS_TRANSPARENCY = "lockscreen_widgets_transparency";

    private static final int[] MAIN_WIDGETS_VIEW_IDS = {
            R.id.main_kg_item_placeholder1,
            R.id.main_kg_item_placeholder2
    };

    private static final int[] WIDGETS_VIEW_IDS = {
            R.id.kg_item_placeholder1,
            R.id.kg_item_placeholder2,
            R.id.kg_item_placeholder3,
            R.id.kg_item_placeholder4
    };

    private static final int BT_ACTIVE = R.drawable.qs_bluetooth_icon_on;
    private static final int BT_INACTIVE = R.drawable.qs_bluetooth_icon_off;
    private static final int DATA_ACTIVE = R.drawable.ic_signal_cellular_alt_24;
    private static final int DATA_INACTIVE = R.drawable.ic_mobiledata_off_24;
    private static final int RINGER_ACTIVE = R.drawable.ic_vibration_24;
    private static final int RINGER_INACTIVE = R.drawable.ic_ring_volume_24;
    private static final int TORCH_RES_ACTIVE = R.drawable.ic_flashlight_on;
    private static final int TORCH_RES_INACTIVE = R.drawable.ic_flashlight_off;
    private static final int WIFI_ACTIVE = R.drawable.ic_wifi_24;
    private static final int WIFI_INACTIVE = R.drawable.ic_wifi_off_24;
    private static final int HOTSPOT_ACTIVE = R.drawable.qs_hotspot_icon_on;
    private static final int HOTSPOT_INACTIVE = R.drawable.qs_hotspot_icon_off;

    private static final int BT_LABEL_INACTIVE = R.string.quick_settings_bluetooth_label;
    private static final int DATA_LABEL_INACTIVE = R.string.quick_settings_data_label;
    private static final int RINGER_LABEL_INACTIVE = R.string.quick_settings_ringer_label;
    private static final int TORCH_LABEL_ACTIVE = R.string.torch_active;
    private static final int TORCH_LABEL_INACTIVE = R.string.quick_settings_flashlight_label;
    private static final int WIFI_LABEL_INACTIVE = R.string.quick_settings_wifi_label;
    private static final int HOTSPOT_LABEL = R.string.accessibility_status_bar_hotspot;

    private final AccessPointController mAccessPointController;
    private final BluetoothController mBluetoothController;
    private final BluetoothTileDialogViewModel mBluetoothTileDialogViewModel;
    private final ConfigurationController mConfigurationController;
    private final DataUsageController mDataController;
    private final FlashlightController mFlashlightController;
    private final InternetDialogManager mInternetDialogManager;
    private final NetworkController mNetworkController;
    private final StatusBarStateController mStatusBarStateController;
    private final MediaSessionManagerHelper mMediaSessionManagerHelper;
    private final LockscreenWidgetsObserver mLockscreenWidgetsObserver;
    private final ActivityLauncherUtils mActivityLauncherUtils;
    private final HotspotController mHotspotController;

    private final CellSignalCallback mCellSignalCallback = new CellSignalCallback();
    private final WifiSignalCallback mWifiSignalCallback = new WifiSignalCallback();
    private final HotspotCallback mHotspotCallback = new HotspotCallback();

    private final Context mContext;
    private final View mView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private LaunchableImageView mWidget1, mWidget2, mWidget3, mWidget4, mediaButton, torchButton, weatherButton;
    private LaunchableFAB mediaButtonFab, torchButtonFab, weatherButtonFab, hotspotButtonFab;
    private LaunchableFAB wifiButtonFab, dataButtonFab, ringerButtonFab, btButtonFab;
    private LaunchableImageView wifiButton, dataButton, ringerButton, btButton, hotspotButton;

    private int mDarkColor, mDarkColorActive, mLightColor, mLightColorActive;

    private CameraManager mCameraManager;
    private String mCameraId;
    private boolean isFlashOn = false;

    private String mMainLockscreenWidgetsList;
    private String mSecondaryLockscreenWidgetsList;
    private LaunchableFAB[] mMainWidgetViews;
    private LaunchableImageView[] mSecondaryWidgetViews;
    private List<String> mMainWidgetsList = new ArrayList<>(2);
    private List<String> mSecondaryWidgetsList = new ArrayList<>(4);

    private AudioManager mAudioManager;
    private String mLastTrackTitle = null;

    private boolean mDozing;
    private boolean mIsInflated = false;
    private boolean mIsLongPress = false;
    private boolean mLockscreenWidgetsEnabled;

    private int mThemeStyle = 0;
    private float mTransparency = 0.3f;

    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            updateWidgetViews();
        }
        @Override
        public void onThemeChanged() {
            updateWidgetViews();
        }
    };

    public LockScreenWidgetsController(View view) {
        mView = view;
        mContext = mView.getContext();

        mAccessPointController = Dependency.get(AccessPointController.class);
        mBluetoothTileDialogViewModel = Dependency.get(BluetoothTileDialogViewModel.class);
        mConfigurationController = Dependency.get(ConfigurationController.class);
        mFlashlightController = Dependency.get(FlashlightController.class);
        mInternetDialogManager = Dependency.get(InternetDialogManager.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mBluetoothController = Dependency.get(BluetoothController.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mDataController = Objects.requireNonNull(mNetworkController).getMobileDataController();
        mHotspotController = Dependency.get(HotspotController.class);
        mMediaSessionManagerHelper = MediaSessionManagerHelper.Companion.getInstance(mContext);

        mActivityLauncherUtils = new ActivityLauncherUtils(mContext);
        mLockscreenWidgetsObserver = new LockscreenWidgetsObserver(mHandler);
        mLockscreenWidgetsObserver.observe();

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        
        initResources();

        if (isWidgetEnabled("weather") && mWeatherClient == null) {
            mWeatherClient = new OmniJawsClient(mContext);
        }

        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            if (cameraIds != null && cameraIds.length > 0) {
                mCameraId = cameraIds[0];
            }
        } catch (Exception e) {
            // Log
        }

        IntentFilter ringerFilter = new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
        mContext.registerReceiver(mRingerModeReceiver, ringerFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {
            // No-op
        }
        
        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing != dozing) {
                mDozing = dozing;
                updateContainerVisibility();
            }
        }
    };

    private final FlashlightController.FlashlightListener mFlashlightCallback =
            new FlashlightController.FlashlightListener() {
        @Override
        public void onFlashlightChanged(boolean enabled) {
            isFlashOn = enabled;
            updateTorchButtonState();
        }
        
        @Override
        public void onFlashlightError() {
            // Handle error
        }
        
        @Override
        public void onFlashlightAvailabilityChanged(boolean available) {
            isFlashOn = mFlashlightController.isEnabled() && available;
            updateTorchButtonState();
        }
    };

    private void initResources() {
        mDarkColor = mContext.getColor(R.color.lockscreen_widget_background_color_dark);
        mLightColor = mContext.getColor(R.color.lockscreen_widget_background_color_light);
        mDarkColorActive = mContext.getColor(R.color.lockscreen_widget_active_color_dark);
        mLightColorActive = mContext.getColor(R.color.lockscreen_widget_active_color_light);
    }
    
    public void registerCallbacks() {
        if (isWidgetEnabled("hotspot")) {
            mHotspotController.addCallback(mHotspotCallback);
        }
        if (isWidgetEnabled("wifi")) {
            mNetworkController.addCallback(mWifiSignalCallback);
        }
        if (isWidgetEnabled("data")) {
            mNetworkController.addCallback(mCellSignalCallback);
        }
        if (isWidgetEnabled("bt")) {
            mBluetoothController.addCallback(mBtCallback);
        }
        if (isWidgetEnabled("torch")) {
            mFlashlightController.addCallback(mFlashlightCallback);
        }
        if (isWidgetEnabled("weather")) {
            enableWeatherUpdates();
        }
        
        mConfigurationController.addCallback(mConfigurationListener);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        mMediaSessionManagerHelper.addMediaMetadataListener(this);
        
        updateWidgetViews();
        updateMediaPlaybackState();
    }
    
    public void unregisterCallbacks() {
        disableWeatherUpdates();
        
        if (isWidgetEnabled("wifi")) {
            mNetworkController.removeCallback(mWifiSignalCallback);
        }
        if (isWidgetEnabled("data")) {
            mNetworkController.removeCallback(mCellSignalCallback);
        }
        if (isWidgetEnabled("bt")) {
            mBluetoothController.removeCallback(mBtCallback);
        }
        if (isWidgetEnabled("torch")) {
            mFlashlightController.removeCallback(mFlashlightCallback);
        }
        if (isWidgetEnabled("hotspot")) {
            mHotspotController.removeCallback(mHotspotCallback);
        }
        
        mConfigurationController.removeCallback(mConfigurationListener);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mContext.unregisterReceiver(mRingerModeReceiver);
        mLockscreenWidgetsObserver.unobserve();
        mHandler.removeCallbacksAndMessages(null);
        mMediaSessionManagerHelper.removeMediaMetadataListener(this);
    }
    
    public void initViews() {
        mMainWidgetViews = new LaunchableFAB[MAIN_WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < MAIN_WIDGETS_VIEW_IDS.length; i++) {
            mMainWidgetViews[i] = mView.findViewById(MAIN_WIDGETS_VIEW_IDS[i]);
        }

        mSecondaryWidgetViews = new LaunchableImageView[WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < WIDGETS_VIEW_IDS.length; i++) {
            mSecondaryWidgetViews[i] = mView.findViewById(WIDGETS_VIEW_IDS[i]);
        }

        mIsInflated = true;
        updateWidgetViews();
    }
    
    public void updateWidgetViews() {
        if (!mIsInflated) return;

        if (mMainWidgetViews != null && mMainWidgetsList != null) {
            updateWidgetSet(mMainWidgetViews, mMainWidgetsList, true);
        }

        if (mSecondaryWidgetViews != null && mSecondaryWidgetsList != null) {
            updateWidgetSet(mSecondaryWidgetViews, mSecondaryWidgetsList, false);
        }

        updateContainerVisibility();
    }

    private void updateWidgetSet(View[] widgetViews, List<String> widgetsList, boolean isMain) {
        int count = Math.min(widgetsList.size(), widgetViews.length);
        
        for (int i = 0; i < widgetViews.length; i++) {
            View widgetView = widgetViews[i];
            if (widgetView != null) {
                widgetView.setVisibility(i < count ? View.VISIBLE : View.GONE);
            }
        }

        for (int i = 0; i < count; i++) {
            String widgetType = widgetsList.get(i);
            if (widgetType != null && i < widgetViews.length && widgetViews[i] != null) {
                if (isMain) {
                    setUpWidgetView(null, (LaunchableFAB) widgetViews[i], widgetType);
                    updateMainWidgetResources((LaunchableFAB) widgetViews[i], false);
                } else {
                    setUpWidgetView((LaunchableImageView) widgetViews[i], null, widgetType);
                    updateWidgetsResources((LaunchableImageView) widgetViews[i]);
                }
            }
        }
    }

    private void updateMainWidgetResources(LaunchableFAB fab, boolean active) {
        if (fab == null) return;
        
        fab.setElevation(0);
        setButtonActiveState(null, fab, false);
        
        long visibleWidgetCount = mMainWidgetsList.stream()
                .filter(widget -> !"none".equals(widget))
                .count();

        ViewGroup.LayoutParams params = fab.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) params;
            if (fab.getVisibility() == View.VISIBLE && visibleWidgetCount == 1) {
                layoutParams.width = mContext.getResources().getDimensionPixelSize(R.dimen.kg_widget_main_width);
                layoutParams.height = mContext.getResources().getDimensionPixelSize(R.dimen.kg_widget_main_height);
            } else {
                layoutParams.width = 0;
                layoutParams.weight = 1;
            }
            fab.setLayoutParams(layoutParams);
        }
    }

    private void updateContainerVisibility() {
        final boolean isMainWidgetsEmpty = TextUtils.isEmpty(mMainLockscreenWidgetsList);
        final boolean isSecondaryWidgetsEmpty = TextUtils.isEmpty(mSecondaryLockscreenWidgetsList);
        final boolean isEmpty = isMainWidgetsEmpty && isSecondaryWidgetsEmpty;
        
        View mainWidgetsContainer = mView.findViewById(R.id.main_widgets_container);
        if (mainWidgetsContainer != null) {
            mainWidgetsContainer.setVisibility(isMainWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        
        View secondaryWidgetsContainer = mView.findViewById(R.id.secondary_widgets_container);
        if (secondaryWidgetsContainer != null) {
            secondaryWidgetsContainer.setVisibility(isSecondaryWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        
        boolean shouldHideContainer = isEmpty || mDozing || !mLockscreenWidgetsEnabled;
        mView.setVisibility(shouldHideContainer ? View.GONE : View.VISIBLE);
    }
    
    private void updateWidgetsResources(LaunchableImageView iv) {
        if (iv == null) return;
        
        int bgRes = (mThemeStyle == 1 || mThemeStyle == 2) 
                ? R.drawable.lockscreen_widget_background_square
                : R.drawable.lockscreen_widget_background_circle;
                
        iv.setBackgroundResource(bgRes);
        setButtonActiveState(iv, null, false);
    }

    private boolean isNightMode() {
        return (mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    
    private void setUpWidgetView(LaunchableImageView iv, LaunchableFAB fab, String type) {
        if (type == null || "none".equals(type)) {
            if (iv != null) iv.setVisibility(View.GONE);
            if (fab != null) fab.setVisibility(View.GONE);
            return;
        }

        View.OnClickListener clickListener = null;
        View.OnLongClickListener longClickListener = null;
        int drawableRes = 0;
        int stringRes = 0;

        switch (type) {
            case "wifi":
                clickListener = v -> toggleWiFi();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = WIFI_INACTIVE;
                stringRes = WIFI_LABEL_INACTIVE;
                if (iv != null) wifiButton = iv;
                if (fab != null) wifiButtonFab = fab;
                break;
            case "data":
                clickListener = v -> toggleMobileData();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = DATA_INACTIVE;
                stringRes = DATA_LABEL_INACTIVE;
                if (iv != null) dataButton = iv;
                if (fab != null) dataButtonFab = fab;
                break;
            case "ringer":
                clickListener = v -> toggleRingerMode();
                drawableRes = RINGER_INACTIVE;
                stringRes = RINGER_LABEL_INACTIVE;
                if (iv != null) ringerButton = iv;
                if (fab != null) ringerButtonFab = fab;
                break;
            case "bt":
                clickListener = v -> toggleBluetoothState();
                longClickListener = v -> {
                    showBluetoothDialog(v);
                    return true;
                };
                drawableRes = BT_INACTIVE;
                stringRes = BT_LABEL_INACTIVE;
                if (iv != null) btButton = iv;
                if (fab != null) btButtonFab = fab;
                break;
            case "torch":
                clickListener = v -> toggleFlashlight();
                drawableRes = TORCH_RES_INACTIVE;
                stringRes = TORCH_LABEL_INACTIVE;
                if (iv != null) torchButton = iv;
                if (fab != null) torchButtonFab = fab;
                break;
            case "timer":
                clickListener = v -> mActivityLauncherUtils.launchTimer();
                drawableRes = R.drawable.ic_alarm;
                stringRes = R.string.clock_timer;
                break;
            case "calculator":
                clickListener = v -> mActivityLauncherUtils.launchCalculator();
                drawableRes = R.drawable.ic_calculator;
                stringRes = R.string.calculator;
                break;
            case "media":
                clickListener = v -> toggleMediaPlaybackState();
                longClickListener = v -> {
                    showMediaDialog(v);
                    return true;
                };
                drawableRes = R.drawable.ic_media_play;
                stringRes = R.string.controls_media_button_play;
                if (iv != null) mediaButton = iv;
                if (fab != null) mediaButtonFab = fab;
                break;
            case "weather":
                clickListener = v -> mActivityLauncherUtils.launchWeatherApp();
                drawableRes = R.drawable.ic_weather;
                stringRes = R.string.weather_data_unavailable;
                if (iv != null) weatherButton = iv;
                if (fab != null) weatherButtonFab = fab;
                break;
            case "hotspot":
                clickListener = v -> toggleHotspot();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = HOTSPOT_INACTIVE;
                stringRes = HOTSPOT_LABEL;
                if (iv != null) hotspotButton = iv;
                if (fab != null) hotspotButtonFab = fab;
                break;
            case "wallet":
                clickListener = v -> mActivityLauncherUtils.launchWalletApp();
                drawableRes = R.drawable.ic_wallet_lockscreen;
                stringRes = R.string.google_wallet;
                break;
            case "qrscanner":
                clickListener = v -> mActivityLauncherUtils.launchQrScanner();
                drawableRes = R.drawable.ic_qr_code_scanner;
                stringRes = R.string.qr_code_scanner_title;
                break;
            default:
                return;
        }

        if (fab != null) {
            fab.setOnClickListener(clickListener);
            fab.setIcon(mContext.getDrawable(drawableRes));
            fab.setText(mContext.getString(stringRes));
            if (longClickListener != null) fab.setOnLongClickListener(longClickListener);
            if (mediaButtonFab == fab) attachSwipeGesture(fab);
        }

        if (iv != null) {
            iv.setOnClickListener(clickListener);
            if (longClickListener != null) iv.setOnLongClickListener(longClickListener);
            iv.setImageResource(drawableRes);
        }
    }

    private void attachSwipeGesture(LaunchableFAB fab) {
        final GestureDetector gestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                        VibrationUtils.triggerVibration(mContext, 2);
                    } else {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
                        VibrationUtils.triggerVibration(mContext, 2);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                mIsLongPress = true;
                showMediaDialog(fab);
                mHandler.postDelayed(() -> mIsLongPress = false, 2500);
            }
        });

        fab.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !mIsLongPress) {
                v.performClick();
            }
            return true;
        });
    }

    private void setButtonActiveState(LaunchableImageView iv, LaunchableFAB fab, boolean active) {
        int bgTint;
        int tintColor;
        
        if (mThemeStyle == 2 || mThemeStyle == 3) {
            bgTint = Utils.applyAlpha(mTransparency, active ? mDarkColorActive : Color.WHITE);
            tintColor = active ? mDarkColorActive : Color.WHITE;
        } else {
            boolean nightMode = isNightMode();
            bgTint = active 
                    ? (nightMode ? mDarkColorActive : mLightColorActive)
                    : (nightMode ? mDarkColor : mLightColor);
            tintColor = active 
                    ? (nightMode ? mDarkColor : mLightColor)
                    : (nightMode ? mLightColor : mDarkColor);
        }
        
        if (iv != null) {
            iv.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            iv.setImageTintList((iv == weatherButton) ? null : ColorStateList.valueOf(tintColor));
        }
        
        if (fab != null) {
            fab.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            fab.setIconTint((fab == weatherButtonFab) ? null : ColorStateList.valueOf(tintColor));
            fab.setTextColor(tintColor);
        }
    }

    private void toggleMediaPlaybackState() {
        int keyCode = mMediaSessionManagerHelper.isMediaPlaying() 
                ? KeyEvent.KEYCODE_MEDIA_PAUSE 
                : KeyEvent.KEYCODE_MEDIA_PLAY;
        dispatchMediaKeyWithWakeLockToMediaSession(keyCode);
    }
    
    private void showMediaDialog(View view) {
        String lastMediaPkg = getLastUsedMedia();
        if (TextUtils.isEmpty(lastMediaPkg)) return;
        
        mHandler.post(() -> {
            ((LockScreenWidgets) mView).showMediaDialog(view, lastMediaPkg);
            VibrationUtils.triggerVibration(mContext, 2);
        });
    }
    
    private String getLastUsedMedia() {
        return Settings.System.getString(mContext.getContentResolver(),
                "media_session_last_package_name");
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) return;
        
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
        
        mHandler.postDelayed(this::updateMediaPlaybackState, 250);
    }

    private void updateMediaPlaybackState() {
        boolean isPlaying = mMediaSessionManagerHelper.isMediaPlaying();
        int stateIcon = isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play;
        
        if (mediaButton != null) {
            mediaButton.setImageResource(stateIcon);
            setButtonActiveState(mediaButton, null, isPlaying);
        }
        
        if (mediaButtonFab != null) {
            MediaMetadata metadata = mMediaSessionManagerHelper.getMediaMetadata();
            String trackTitle = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
            if (!TextUtils.isEmpty(trackTitle)) {
                mLastTrackTitle = trackTitle;
            }
            
            boolean canShowTrackTitle = isPlaying || !TextUtils.isEmpty(mLastTrackTitle);
            mediaButtonFab.setIcon(mContext.getDrawable(stateIcon));
            mediaButtonFab.setText(canShowTrackTitle ? mLastTrackTitle : mContext.getString(R.string.controls_media_button_play));
            setButtonActiveState(null, mediaButtonFab, isPlaying);
        }
    }

    private void toggleFlashlight() {
        if (torchButton == null && torchButtonFab == null) return;
        
        try {
            boolean newState = !isFlashOn;
            mCameraManager.setTorchMode(mCameraId, newState);
            isFlashOn = newState;
            updateTorchButtonState();
        } catch (Exception e) {
            // Handle error
        }
    }

    private void toggleWiFi() {
        final WifiCallbackInfo cbi = mWifiSignalCallback.mInfo;
        boolean newState = !cbi.enabled;
        mNetworkController.setWifiEnabled(newState);
        updateWiFiButtonState(newState);
        mHandler.postDelayed(() -> updateWiFiButtonState(cbi.enabled), 250);
    }

    private boolean isMobileDataEnabled() {
        return mDataController.isMobileDataEnabled();
    }

    private void toggleMobileData() {
        boolean newState = !isMobileDataEnabled();
        mDataController.setMobileDataEnabled(newState);
        updateMobileDataState(newState);
        mHandler.postDelayed(() -> updateMobileDataState(isMobileDataEnabled()), 250);
    }
    
    private void showInternetDialog(View view) {
        mHandler.post(() -> {
            mInternetDialogManager.create(true,
                    mAccessPointController.canConfigMobileData(),
                    mAccessPointController.canConfigWifi(), 
                    Expandable.fromView(view));
            VibrationUtils.triggerVibration(mContext, 2);
        });
    }

    private void toggleRingerMode() {
        if (mAudioManager == null) return;
        
        int mode = mAudioManager.getRingerMode();
        int newMode = (mode == AudioManager.RINGER_MODE_NORMAL) 
                ? AudioManager.RINGER_MODE_VIBRATE 
                : AudioManager.RINGER_MODE_NORMAL;
        mAudioManager.setRingerMode(newMode);
        updateRingerButtonState();
    }

    private void updateTileButtonState(
            LaunchableImageView iv, LaunchableFAB fab, 
            boolean active, int activeResource, int inactiveResource,
            String activeString, String inactiveString) {
            
        mHandler.post(() -> {
            if (iv != null) {
                iv.setImageResource(active ? activeResource : inactiveResource);
                setButtonActiveState(iv, null, active);
            }
            if (fab != null) {
                fab.setIcon(mContext.getDrawable(active ? activeResource : inactiveResource));
                fab.setText(active ? activeString : inactiveString);
                setButtonActiveState(null, fab, active);
            }
        });
    }
    
    public void updateTorchButtonState() {
        if (!isWidgetEnabled("torch")) return;
        
        String activeString = mContext.getString(TORCH_LABEL_ACTIVE);
        String inactiveString = mContext.getString(TORCH_LABEL_INACTIVE);
        updateTileButtonState(torchButton, torchButtonFab, isFlashOn, 
                TORCH_RES_ACTIVE, TORCH_RES_INACTIVE, activeString, inactiveString);
    }

    private final BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerButtonState();
        }
    };

    private final BluetoothController.Callback mBtCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            updateBtState();
        }
        
        @Override
        public void onBluetoothDevicesChanged() {
            updateBtState();
        }
    };

    private void updateWiFiButtonState(boolean enabled) {
        if (!isWidgetEnabled("wifi")) return;
        if (wifiButton == null && wifiButtonFab == null) return;
        
        final WifiCallbackInfo cbi = mWifiSignalCallback.mInfo;
        String inactiveString = mContext.getString(WIFI_LABEL_INACTIVE);
        String displayText = (cbi.ssid != null) ? removeDoubleQuotes(cbi.ssid) : inactiveString;
        
        updateTileButtonState(wifiButton, wifiButtonFab, enabled, 
                WIFI_ACTIVE, WIFI_INACTIVE, displayText, inactiveString);
    }

    private void updateRingerButtonState() {
        if (!isWidgetEnabled("ringer")) return;
        if (ringerButton == null && ringerButtonFab == null) return;
        
        if (mAudioManager != null) {
            boolean isVibrateActive = mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;
            String inactiveString = mContext.getString(RINGER_LABEL_INACTIVE);
            
            updateTileButtonState(ringerButton, ringerButtonFab, isVibrateActive, 
                    RINGER_ACTIVE, RINGER_INACTIVE, inactiveString, inactiveString);
        }
    }

    private void updateMobileDataState(boolean enabled) {
        if (!isWidgetEnabled("data")) return;
        if (dataButton == null && dataButtonFab == null) return;
        
        String networkName = (mNetworkController != null) ? mNetworkController.getMobileDataNetworkName() : "";
        boolean hasNetwork = !TextUtils.isEmpty(networkName) && mNetworkController != null 
                && mNetworkController.hasMobileDataFeature();
        String inactiveString = mContext.getString(DATA_LABEL_INACTIVE);
        String displayText = (hasNetwork && enabled) ? networkName : inactiveString;
        
        updateTileButtonState(dataButton, dataButtonFab, enabled, 
                DATA_ACTIVE, DATA_INACTIVE, displayText, inactiveString);
    }
    
    private void toggleBluetoothState() {
        boolean newState = !isBluetoothEnabled();
        mBluetoothController.setBluetoothEnabled(newState);
        updateBtState();
        mHandler.postDelayed(this::updateBtState, 250);
    }
    
    private void showBluetoothDialog(View view) {
        mHandler.post(() -> {
            mBluetoothTileDialogViewModel.showDialog(Expandable.fromView(view));
            VibrationUtils.triggerVibration(mContext, 2);
        });
    }
    
    private void updateBtState() {
        if (!isWidgetEnabled("bt")) return;
        if (btButton == null && btButtonFab == null) return;
        
        String deviceName = isBluetoothEnabled() ? mBluetoothController.getConnectedDeviceName() : "";
        boolean isConnected = !TextUtils.isEmpty(deviceName);
        String inactiveString = mContext.getString(BT_LABEL_INACTIVE);
        String displayText = isConnected ? deviceName : inactiveString;
        
        updateTileButtonState(btButton, btButtonFab, isBluetoothEnabled(), 
                BT_ACTIVE, BT_INACTIVE, displayText, inactiveString);
    }
    
    private boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    @Nullable
    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        
        int length = string.length();
        if (length > 1 && string.charAt(0) == '"' && string.charAt(length - 1) == '"') {
            return string.substring(1, length - 1);
        }
        return string;
    }

    protected static final class WifiCallbackInfo {
        boolean enabled;
        @Nullable
        String ssid;
    }

    protected final class WifiSignalCallback implements SignalCallback {
        final WifiCallbackInfo mInfo = new WifiCallbackInfo();
        
        @Override
        public void setWifiIndicators(@NonNull WifiIndicators indicators) {
            if (indicators.qsIcon == null) {
                updateWiFiButtonState(false);
                return;
            }
            
            mInfo.enabled = indicators.enabled;
            mInfo.ssid = indicators.description;
            updateWiFiButtonState(mInfo.enabled);
        }
    }

    private final class CellSignalCallback implements SignalCallback {
        @Override
        public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
            if (indicators.qsIcon == null) {
                updateMobileDataState(false);
                return;
            }
            updateMobileDataState(isMobileDataEnabled());
        }
        
        @Override
        public void setNoSims(boolean show, boolean simDetected) {
            updateMobileDataState(simDetected && isMobileDataEnabled());
        }
        
        @Override
        public void setIsAirplaneMode(@NonNull IconState icon) {
            updateMobileDataState(!icon.visible && isMobileDataEnabled());
        }
    }
    
    public void enableWeatherUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.addObserver(this);
            queryAndUpdateWeather();
        }
    }

    public void disableWeatherUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(this);
        }
    }

    @Override
    public void weatherError(int errorReason) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
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
        try {
            if (mWeatherClient == null || !mWeatherClient.isOmniJawsEnabled()) return;
            
            mWeatherClient.queryWeather();
            mWeatherInfo = mWeatherClient.getWeatherInfo();
            
            if (mWeatherInfo != null) {
                String formattedCondition = formatWeatherCondition(mWeatherInfo.condition);
                Drawable weatherIcon = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);
                
                if (weatherButtonFab != null) {
                    weatherButtonFab.setIcon(weatherIcon);
                    weatherButtonFab.setText(mWeatherInfo.temp + mWeatherInfo.tempUnits + " \u2022 " + formattedCondition);
                    weatherButtonFab.setIconTint(null);
                }
                
                if (weatherButton != null) {
                    weatherButton.setImageDrawable(weatherIcon);
                    weatherButton.setImageTintList(null);
                }
            }
        } catch(Exception e) {
            // Log
        }
    }
    
    private String formatWeatherCondition(String condition) {
        if (condition == null) return "";
        
        String lcCondition = condition.toLowerCase();
        if (lcCondition.contains("clouds")) {
            return mContext.getString(R.string.weather_condition_clouds);
        } else if (lcCondition.contains("rain")) {
            return mContext.getString(R.string.weather_condition_rain);
        } else if (lcCondition.contains("clear")) {
            return mContext.getString(R.string.weather_condition_clear);
        } else if (lcCondition.contains("storm")) {
            return mContext.getString(R.string.weather_condition_storm);
        } else if (lcCondition.contains("snow")) {
            return mContext.getString(R.string.weather_condition_snow);
        } else if (lcCondition.contains("wind")) {
            return mContext.getString(R.string.weather_condition_wind);
        } else if (lcCondition.contains("mist")) {
            return mContext.getString(R.string.weather_condition_mist);
        }
        
        if (condition.contains("_")) {
            String[] words = condition.split("_");
            StringBuilder builder = new StringBuilder();
            for (String word : words) {
                if (word.length() > 0) {
                    builder.append(Character.toUpperCase(word.charAt(0)))
                           .append(word.substring(1).toLowerCase())
                           .append(" ");
                }
            }
            return builder.toString().trim();
        }
        
        return condition;
    }
        
    private boolean isWidgetEnabled(String widget) {
        return (mMainLockscreenWidgetsList != null && mMainLockscreenWidgetsList.contains(widget)) 
                || (mSecondaryLockscreenWidgetsList != null && mSecondaryLockscreenWidgetsList.contains(widget));
    }
    
    @Override
    public void onMediaMetadataChanged() {
        updateMediaPlaybackState();
    }

    @Override
    public void onPlaybackStateChanged() {
        updateMediaPlaybackState();
    }
    
    private class LockscreenWidgetsObserver extends ContentObserver {
        private final ContentResolver mResolver;
        
        public LockscreenWidgetsObserver(Handler handler) {
            super(handler);
            mResolver = mContext.getContentResolver();
        }
        
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
        
        void observe() {
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_ENABLED), 
                    false, 
                    this);
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS), 
                    false, 
                    this);
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_EXTRAS), 
                    false, 
                    this);
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_STYLE), 
                    false, 
                    this);
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_TRANSPARENCY), 
                    false, 
                    this);
            updateSettings();
        }
        
        void unobserve() {
            mResolver.unregisterContentObserver(this);
        }
        
        void updateSettings() {
            mLockscreenWidgetsEnabled = Settings.System.getInt(mResolver, 
                    LOCKSCREEN_WIDGETS_ENABLED, 0) == 1;
            mMainLockscreenWidgetsList = Settings.System.getString(mResolver, 
                    LOCKSCREEN_WIDGETS);
            mSecondaryLockscreenWidgetsList = Settings.System.getString(mResolver, 
                    LOCKSCREEN_WIDGETS_EXTRAS);
            mThemeStyle = Settings.System.getInt(mResolver, 
                    LOCKSCREEN_WIDGETS_STYLE, 0);
            mTransparency = Settings.System.getInt(mResolver, 
                    LOCKSCREEN_WIDGETS_TRANSPARENCY, 30) / 100f;
            
            mMainWidgetsList.clear();
            if (mMainLockscreenWidgetsList != null) {
                mMainWidgetsList.addAll(Arrays.asList(mMainLockscreenWidgetsList.split(",")));
            }
            
            mSecondaryWidgetsList.clear();
            if (mSecondaryLockscreenWidgetsList != null) {
                mSecondaryWidgetsList.addAll(Arrays.asList(mSecondaryLockscreenWidgetsList.split(",")));
            }
            
            updateWidgetViews();
        }
    }

    private void updateHotspotState() {
        if (!isWidgetEnabled("hotspot")) return;
        if (hotspotButton == null && hotspotButtonFab == null) return;
        
        String hotspotString = mContext.getString(HOTSPOT_LABEL);
        updateTileButtonState(hotspotButton, hotspotButtonFab, mHotspotController.isHotspotEnabled(), 
                HOTSPOT_ACTIVE, HOTSPOT_INACTIVE, hotspotString, hotspotString);
    }

    private void toggleHotspot() {
        boolean newState = !mHotspotController.isHotspotEnabled();
        mHotspotController.setHotspotEnabled(newState);
        updateHotspotState();
        mHandler.postDelayed(this::updateHotspotState, 250);
    }
    
    private final class HotspotCallback implements HotspotController.Callback {
        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            updateHotspotState();
        }
        
        @Override
        public void onHotspotAvailabilityChanged(boolean available) {
            // No-op
        }
    }
}
