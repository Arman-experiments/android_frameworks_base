/*
 * Copyright (C) 2023-2024 The RisingOS Android Project
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
package com.android.systemui.theme;

import android.provider.Settings;

public class CustomSettingsConstants {
    public static final String STATUS_BAR_LOGO = "status_bar_logo";
    public static final String STATUS_BAR_LOGO_POSITION = "status_bar_logo_position";
    public static final String VOLUME_STYLE = "custom_volume_styles";
    public static final String VOLUME_TEXTVIEW = "VOLUME_TEXTVIEW";
    public static final String VOLUME_TEXTVIEW_STYLE = "VOLUME_TEXTVIEW_STYLE";
    public static final String CLOCK_STYLE = "clock_style";
    public static final String LOCKSCREEN_WIDGETS_ENABLED = "lockscreen_widgets_enabled";
    public static final String LOCKSCREEN_WIDGETS = "lockscreen_widgets";
    public static final String LOCKSCREEN_WIDGETS_EXTRAS = "lockscreen_widgets_extras";
    public static final String QS_TILE_UI_STYLE = "qs_tile_ui_style";

    public static final String[] SYSTEM_SETTINGS_KEYS = {
        STATUS_BAR_LOGO,
        STATUS_BAR_LOGO_POSITION,
        VOLUME_TEXTVIEW,
        VOLUME_TEXTVIEW_STYLE,
        LOCKSCREEN_WIDGETS_ENABLED,
        LOCKSCREEN_WIDGETS,
        LOCKSCREEN_WIDGETS_EXTRAS,
        "lockscreen_widgets_style",
        "lockscreen_widgets_transparency",
        QS_TILE_UI_STYLE,
    };
    
    public static final String[] SECURE_SETTINGS_KEYS = {
        CLOCK_STYLE,
    };
    
    public static final String[] SYSTEM_SETTINGS_NOTIFY_ONLY_KEYS = {
        VOLUME_STYLE

    };
    
    public static final String[] SECURE_SETTINGS_NOTIFY_ONLY_KEYS = {
    };
}
