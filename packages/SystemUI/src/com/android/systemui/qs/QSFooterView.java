/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.BidiFormatter;
import android.text.format.Formatter;
import android.text.format.Formatter.BytesResult;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.net.DataUsageController;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.res.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

public class QSFooterView extends FrameLayout {
    private PageIndicator mPageIndicator;
    private TextView mUsageText;
    private TextView mFooterText;
    private View mEditButton;
    private View mSpace;
    
    private static final String PREF_NAME = "DailyUsagePrefs";
    private static final String KEY_MOBILE_UPLOAD = "mobile_upload";
    private static final String KEY_MOBILE_DOWNLOAD = "mobile_download";
    private static final String KEY_WIFI_UPLOAD = "wifi_upload";
    private static final String KEY_WIFI_DOWNLOAD = "wifi_download";
    private static final String KEY_LAST_RESET = "last_reset";

    private LinearLayout mDataUsagePanel;

    @Nullable
    protected TouchAnimator mFooterAnimator;

    private boolean mQsDisabled;
    private boolean mExpanded;
    private float mExpansionAmount;

    @Nullable
    private OnClickListener mExpandClickListener;

    private DataUsageController mDataController;
    private ConnectivityManager mConnectivityManager;
    private WifiManager mWifiManager;
    private SubscriptionManager mSubManager;
    private boolean mShouldShowDataUsage;
    private Handler mHandler = new Handler();
    
    private Runnable mUpdateDataUsageRunnable = new Runnable() {
        @Override
        public void run() {
            setUsageText();
            mHandler.postDelayed(this, 10000);
        }
    };

    public QSFooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDataController = new DataUsageController(context);
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mSubManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPageIndicator = findViewById(R.id.footer_page_indicator);
        mUsageText = findViewById(R.id.usage_text);
        mFooterText = findViewById(R.id.footer_text);
        mEditButton = findViewById(android.R.id.edit);
        mSpace = findViewById(R.id.spacer);
        mDataUsagePanel = findViewById(R.id.qs_data_usage);

        updateResources();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateFooterTextVisibility();
        setUsageText();
        getInternetUsage();
    }

    private void updateFooterTextVisibility() {
        boolean isShow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.OMNI_FOOTER_TEXT_SHOW, 0,
                UserHandle.USER_CURRENT) == 1;
        mShouldShowDataUsage = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_FOOTER_DATA_USAGE, 0,
                UserHandle.USER_CURRENT) == 1;

        if (isShow) {
            mUsageText.setVisibility(View.GONE);
            mFooterText.setVisibility(View.VISIBLE);
            mSpace.setVisibility(View.GONE);
            setFooterText();
        } else if (mShouldShowDataUsage) {
	    mFooterText.setVisibility(View.GONE);
	    mUsageText.setVisibility(View.VISIBLE);
	    mSpace.setVisibility(View.GONE);
	    setUsageText();
	} else {
	    mFooterText.setVisibility(View.GONE);
	    mUsageText.setVisibility(View.GONE);
	    mSpace.setVisibility(View.VISIBLE);
        }
    }

    private void setFooterText() {
        String text = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.X_FOOTER_TEXT_STRING,
                UserHandle.USER_CURRENT);
        if (text == null || text.isEmpty()) {
            mFooterText.setText("Infinity X");
        } else {
            mFooterText.setText(text);
        }
    }
    
    private void setUsageText() {
        if (mUsageText == null) return;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            DataUsageController.DataUsageInfo info;
            if (isWifiConnected()) {
                info = mDataController.getWifiDailyDataUsageInfo();
            } else {
                mDataController.setSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId());
                info = mDataController.getDailyDataUsageInfo();
            }

            DataUsageController.DataUsageInfo finalInfo = info;
            handler.post(() -> {
                if (info != null) {
                    String suffix = isWifiConnected() ?
                            mContext.getResources().getString(R.string.usage_wifi_default_suffix) :
                            mContext.getResources().getString(R.string.usage_data_default_suffix);
                    mUsageText.setText(formatDataUsage(info.usageLevel) + " " +
                            mContext.getResources().getString(R.string.usage_data) +
                            " (" + suffix + ")");
                } else {
                    mUsageText.setText(" ");
                }
            });
        });
    }

    private CharSequence formatDataUsage(long byteValue) {
        final BytesResult res = Formatter.formatBytes(mContext.getResources(), byteValue,
                Formatter.FLAG_IEC_UNITS);
        return BidiFormatter.getInstance().unicodeWrap(mContext.getString(
                com.android.internal.R.string.fileSizeSuffix, res.value, res.units));
    }

    private boolean isWifiConnected() {
        final Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            return false;
        }
    }

    private void getInternetUsage() {
        TextView internetUp = findViewById(R.id.internet_up);
	TextView internetDown = findViewById(R.id.internet_down);
	TextView mobileUp = findViewById(R.id.mobile_up);
	TextView mobileDown = findViewById(R.id.mobile_down);
	TextView wifiUp = findViewById(R.id.wifi_up);
	TextView wifiDown = findViewById(R.id.wifi_down);
	
	DataUsageController.DataUsageInfo mobileUploadInfo = mDataController.getDailyDataUsageUploadInfo();
        DataUsageController.DataUsageInfo mobileDownloadInfo = mDataController.getDailyDataUsageDownloadInfo();
        DataUsageController.DataUsageInfo wifiUploadInfo = mDataController.getWifiDailyDataUsageUploadInfo();
        DataUsageController.DataUsageInfo wifiDownloadInfo = mDataController.getWifiDailyDataUsageDownloadInfo();

        long mobileUpload = mobileUploadInfo != null ? mobileUploadInfo.usageLevel : 0;
        long mobileDownload = mobileDownloadInfo != null ? mobileDownloadInfo.usageLevel : 0;
        long wifiUpload = wifiUploadInfo != null ? wifiUploadInfo.usageLevel : 0;
        long wifiDownload = wifiDownloadInfo != null ? wifiDownloadInfo.usageLevel : 0;

        long totalUpload = mobileUpload + wifiUpload;
        long totalDownload = mobileDownload + wifiDownload;

        internetDown.setText("Download: " + formatDataSize(totalDownload));
        internetUp.setText("Upload: " + formatDataSize(totalUpload));

        mobileDown.setText("Download: " + formatDataSize(mobileDownload));
        mobileUp.setText("Upload: " + formatDataSize(mobileUpload));

        wifiDown.setText("Download: " + formatDataSize(wifiDownload));
        wifiUp.setText("Upload: " + formatDataSize(wifiUpload));
    }
    
    private String formatDataSize(long bytes) {
        if (bytes < 1024) {
           return bytes + " B";
        } else if (bytes < 1024 * 1024) {
           return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
           return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
           return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void updateResources() {
        updateFooterAnimator();
        updateEditButtonResources();
        updateBuildTextResources();
        updateUsageTextResources();
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.height = !showUsagePanel() ?
                getResources().getDimensionPixelSize(R.dimen.qs_footer_height) :
                ViewGroup.LayoutParams.WRAP_CONTENT;
        int sideMargin = getResources().getDimensionPixelSize(R.dimen.qs_footer_margin);
        lp.leftMargin = sideMargin;
        lp.rightMargin = sideMargin;
        lp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.qs_footers_margin_bottom);
        setLayoutParams(lp);
    }

    private void updateEditButtonResources() {
        int size = getResources().getDimensionPixelSize(R.dimen.qs_footer_action_button_size);
        int padding = getResources().getDimensionPixelSize(R.dimen.qs_footer_icon_padding);
        MarginLayoutParams lp = (MarginLayoutParams) mEditButton.getLayoutParams();
        lp.height = size;
        lp.width = size;
        mEditButton.setLayoutParams(lp);
        mEditButton.setPadding(padding, padding, padding, padding);
    }

    private void updateBuildTextResources() {
        FontSizeUtils.updateFontSizeFromStyle(mFooterText, R.style.TextAppearance_QS_Status_Build);
    }
    
    private void updateUsageTextResources() {
        FontSizeUtils.updateFontSizeFromStyle(mUsageText, R.style.TextAppearance_QS_Status_Build);
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        TouchAnimator.Builder builder = new TouchAnimator.Builder()
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .addFloat(mUsageText, "alpha", 0, 1)
                .addFloat(mFooterText, "alpha", 0, 1)
                .addFloat(mEditButton, "alpha", 0, 1)
                .addFloat(mDataUsagePanel, "alpha", 0, 1)           
                .setStartDelay(0.8f);                
        return builder.build();
    }

    public void setKeyguardShowing() {
        setExpansion(mExpansionAmount);
    }

    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }

        if (mUsageText == null) return;
        if (mShouldShowDataUsage && headerExpansionFraction == 1.0f) {
            mUsageText.postDelayed(() -> mUsageText.setSelected(true), 1000);
        } else {
            mUsageText.setSelected(false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.OMNI_FOOTER_TEXT_SHOW), false,
                mSettingsObserver, UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.X_FOOTER_TEXT_STRING), false,
                mSettingsObserver, UserHandle.USER_ALL);
                
        mHandler.post(mUpdateDataUsageRunnable);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mUpdateDataUsageRunnable);
    }

    void disable(int state2) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    void updateEverything() {
        post(() -> {
            updateVisibilities();
            setClickable(false);
        });
    }
    
    private boolean showUsagePanel() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_DATA_USAGE_PANEL, 0,
                UserHandle.USER_CURRENT) == 1;
    }
    
    private void updateVisibilities() {
        if (mExpanded) {
            updateFooterTextVisibility();
        } else {
            mUsageText.setVisibility(View.INVISIBLE);
            mFooterText.setVisibility(View.INVISIBLE);
        }
        
        if (mExpanded && showUsagePanel()) {
            mDataUsagePanel.setVisibility(View.VISIBLE);
            getInternetUsage();
        } else {
            mDataUsagePanel.setVisibility(View.GONE);
        }
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateVisibilities();
        }
    };
}
