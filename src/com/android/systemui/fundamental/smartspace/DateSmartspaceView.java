/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 FundamentalOS
 *
 * Lean stand-in for com.google.android.systemui.smartspace.DateSmartspaceView. The Google view
 * (IcuDateTextView + DoubleShadowTextView + DoubleShadowIconDrawable + logging/*) is replaced by a
 * self-updating ICU date row. It MUST remain a LinearLayout: SmartspaceSection casts the date view
 * to LinearLayout and inserts the weather view as a sibling right after the date text.
 */
package com.android.systemui.fundamental.smartspace;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import java.util.Locale;
import java.util.Objects;

public class DateSmartspaceView extends LinearLayout
        implements BcSmartspaceDataPlugin.SmartspaceView {

    /** ICU skeleton for the lockscreen date, e.g. "Wed, Sep 3". */
    private static final String DATE_SKELETON = "EEEMMMd";
    private static final long MINUTE_MS = 60_000L;

    private final TextView mDateView;

    @Nullable private TextView mAlarmView;
    @Nullable private ImageView mDndView;

    @Nullable private DateFormat mFormatter;
    @Nullable private String mCurrentText;
    private int mPrimaryTextColor = Color.WHITE;

    @Nullable private BcSmartspaceDataPlugin.TimeChangedDelegate mTimeChangedDelegate;
    @Nullable private Handler mFallbackHandler;

    private final Runnable mUpdateTime = this::onTimeChanged;
    private final Runnable mFallbackTick =
            new Runnable() {
                @Override
                public void run() {
                    onTimeChanged();
                    if (mFallbackHandler != null) {
                        long now = SystemClock.uptimeMillis();
                        mFallbackHandler.postAtTime(this, now + (MINUTE_MS - now % MINUTE_MS));
                    }
                }
            };

    public DateSmartspaceView(Context context) {
        this(context, null);
    }

    public DateSmartspaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        mDateView = new TextView(context);
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mDateView.setSingleLine(true);
        mDateView.setTextColor(mPrimaryTextColor);
        applyTextStyle(mDateView);
        // Index 0: SmartspaceSection inserts the weather view at index 1 (right after the date).
        addView(mDateView,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /**
     * Give a smartspace text row its shared look: medium weight plus a soft dark shadow so it stays
     * legible over a bright wallpaper. The stock Google view used a DoubleShadowTextView.
     */
    static void applyTextStyle(TextView view) {
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float density = view.getResources().getDisplayMetrics().density;
        view.setShadowLayer(2f * density, 0f, density, 0xB2000000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTicking();
        onTimeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTicking();
    }

    private void startTicking() {
        if (mTimeChangedDelegate != null) {
            mTimeChangedDelegate.register(mUpdateTime);
        } else {
            if (mFallbackHandler == null) {
                mFallbackHandler = new Handler(Looper.getMainLooper());
            }
            mFallbackHandler.removeCallbacks(mFallbackTick);
            mFallbackTick.run();
        }
    }

    private void stopTicking() {
        if (mTimeChangedDelegate != null) {
            mTimeChangedDelegate.unregister();
        }
        if (mFallbackHandler != null) {
            mFallbackHandler.removeCallbacks(mFallbackTick);
        }
    }

    private void onTimeChanged() {
        if (mFormatter == null) {
            mFormatter = DateFormat.getInstanceForSkeleton(DATE_SKELETON, Locale.getDefault());
            mFormatter.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE);
        }
        String text = mFormatter.format(Long.valueOf(System.currentTimeMillis()));
        if (Objects.equals(mCurrentText, text)) {
            return;
        }
        mCurrentText = text;
        mDateView.setText(text);
        mDateView.setContentDescription(text);
    }

    @Override
    public void setTimeChangedDelegate(BcSmartspaceDataPlugin.TimeChangedDelegate delegate) {
        // Re-register against the new delegate if we are already ticking.
        boolean attached = isAttachedToWindow();
        if (attached) {
            stopTicking();
        }
        mTimeChangedDelegate = delegate;
        if (attached) {
            startTicking();
        }
    }

    @Override
    public void setNextAlarm(@Nullable Drawable image, @Nullable String description) {
        if (TextUtils.isEmpty(description)) {
            if (mAlarmView != null) {
                mAlarmView.setVisibility(GONE);
            }
            return;
        }
        if (mAlarmView == null) {
            mAlarmView = new TextView(getContext());
            mAlarmView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            mAlarmView.setSingleLine(true);
            mAlarmView.setTextColor(mPrimaryTextColor);
            applyTextStyle(mAlarmView);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            addView(mAlarmView, lp); // appended after date (and weather, if present)
        }
        mAlarmView.setText(description);
        mAlarmView.setContentDescription(description);
        mAlarmView.setCompoundDrawablesRelativeWithIntrinsicBounds(image, null, null, null);
        mAlarmView.setVisibility(VISIBLE);
    }

    @Override
    public void setDnd(@Nullable Drawable image, @Nullable String description) {
        if (image == null) {
            if (mDndView != null) {
                mDndView.setVisibility(GONE);
            }
            return;
        }
        if (mDndView == null) {
            mDndView = new ImageView(getContext());
            addView(mDndView,
                    new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        mDndView.setImageDrawable(image);
        mDndView.setContentDescription(description);
        mDndView.setVisibility(VISIBLE);
    }

    @Override
    public void registerDataProvider(BcSmartspaceDataPlugin plugin) {
        // The date is self-driven; it does not consume smartspace targets.
    }

    @Override
    public void setPrimaryTextColor(int color) {
        mPrimaryTextColor = color;
        mDateView.setTextColor(color);
        if (mAlarmView != null) {
            mAlarmView.setTextColor(color);
        }
    }

    @Override
    public void setUiSurface(String uiSurface) {
    }

    @Override
    public void setBgHandler(Handler bgHandler) {
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
