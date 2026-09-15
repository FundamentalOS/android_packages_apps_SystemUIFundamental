/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 FundamentalOS
 *
 * Lean stand-in for com.google.android.systemui.smartspace.WeatherSmartspaceView. The Google view
 * pulls in generated proto (SmartspaceProto), R$styleable attrs, DoubleShadow* and logging/*; this
 * one renders the weather target's header text + icon and registers as a target listener on the
 * weather plugin (which pre-filters FEATURE_WEATHER). It is inserted as a child of the date view by
 * SmartspaceSection.
 */
package com.android.systemui.fundamental.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import java.util.List;

public class WeatherSmartspaceView extends LinearLayout
        implements BcSmartspaceDataPlugin.SmartspaceView,
                BcSmartspaceDataPlugin.SmartspaceTargetListener {

    private final TextView mWeatherView;

    private BcSmartspaceDataPlugin mDataProvider;
    private int mPrimaryTextColor = Color.WHITE;

    public WeatherSmartspaceView(Context context) {
        this(context, null);
    }

    public WeatherSmartspaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        // Sit a gap clear of the date text so the weather glyph does not butt against it.
        setPaddingRelative(
                getResources().getDimensionPixelSize(
                        R.dimen.fundamental_smartspace_date_weather_gap), 0, 0, 0);

        mWeatherView = new TextView(context);
        mWeatherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mWeatherView.setSingleLine(true);
        mWeatherView.setTextColor(mPrimaryTextColor);
        DateSmartspaceView.applyTextStyle(mWeatherView);
        mWeatherView.setCompoundDrawablePadding(
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f,
                        context.getResources().getDisplayMetrics()));
        addView(mWeatherView,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mDataProvider != null) {
            mDataProvider.registerListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
    }

    @Override
    public void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets) {
        SmartspaceTarget target = null;
        for (Parcelable p : targets) {
            if (p instanceof SmartspaceTarget
                    && ((SmartspaceTarget) p).getFeatureType() == SmartspaceTarget.FEATURE_WEATHER) {
                target = (SmartspaceTarget) p;
                break;
            }
        }
        final SmartspaceAction header = (target != null) ? target.getHeaderAction() : null;
        final CharSequence text = (header != null) ? header.getTitle() : null;
        if (TextUtils.isEmpty(text)) {
            setVisibility(GONE);
            setOnClickListener(null);
            return;
        }
        mWeatherView.setText(text);
        mWeatherView.setContentDescription(
                header.getContentDescription() != null ? header.getContentDescription() : text);

        Drawable iconDrawable = null;
        Icon icon = header.getIcon();
        if (icon != null) {
            iconDrawable = icon.loadDrawable(getContext());
        }
        if (iconDrawable != null) {
            // Weather glyphs arrive as full-size bitmaps; size them like stock's smartspace icon
            // instead of using their intrinsic bounds.
            int size = getResources().getDimensionPixelSize(
                    R.dimen.fundamental_smartspace_icon_size);
            iconDrawable.setBounds(0, 0, size, size);
        }
        mWeatherView.setCompoundDrawablesRelative(iconDrawable, null, null, null);

        setVisibility(VISIBLE);
        final SmartspaceAction tapAction = header;
        setOnClickListener(v -> launch(v, tapAction));
    }

    private void launch(View v, SmartspaceAction action) {
        if (mDataProvider == null) {
            return;
        }
        BcSmartspaceDataPlugin.SmartspaceEventNotifier notifier = mDataProvider.getEventNotifier();
        BcSmartspaceDataPlugin.IntentStarter starter =
                (notifier != null) ? notifier.getIntentStarter() : null;
        if (starter != null) {
            starter.startFromAction(action, v, /* showOnLockscreen= */ false);
        }
    }

    @Override
    public void registerDataProvider(BcSmartspaceDataPlugin plugin) {
        mDataProvider = plugin;
    }

    @Override
    public void setPrimaryTextColor(int color) {
        mPrimaryTextColor = color;
        mWeatherView.setTextColor(color);
    }

    @Override
    public void setUiSurface(String uiSurface) {
    }

    @Override
    public void setBgHandler(android.os.Handler bgHandler) {
    }

    @Override
    public void setDozeAmount(float amount) {
    }

    @Override
    public void setFalsingManager(FalsingManager falsingManager) {
    }

    @Override
    public int getSelectedPage() {
        return 0;
    }

    @Override
    public int getCurrentCardTopPadding() {
        return 0;
    }
}
