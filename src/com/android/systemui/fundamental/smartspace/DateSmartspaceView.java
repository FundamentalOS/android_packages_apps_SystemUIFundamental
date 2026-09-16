/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 FundamentalOS
 *
 * Lean stand-in for com.google.android.systemui.smartspace.DateSmartspaceView. The Google view
 * (IcuDateTextView + DoubleShadowTextView + DoubleShadowIconDrawable + logging/*) is replaced by a
 * self-updating ICU date row. It MUST remain a LinearLayout: SmartspaceSection casts the date view
 * to LinearLayout and inserts the weather view as a sibling right after the date text.
 *
 * Layout. The Do Not Disturb glyph, the date and the weather chip share one horizontal line
 * (mRow, with the DnD glyph leftmost, before the date). The next-alarm chip is the outer view's
 * second child, so the keyguard section's orientation decides where it lands: inline after the row
 * when the section lays this view out horizontally (a row below a small clock, room to spare), and
 * on its own line when the section stacks the view vertically beside a large clock (no room for the
 * alarm on the first line).
 */
package com.android.systemui.fundamental.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class DateSmartspaceView extends LinearLayout
        implements BcSmartspaceDataPlugin.SmartspaceView,
                BcSmartspaceDataPlugin.SmartspaceTargetListener {

    /** ICU skeleton for the lockscreen date, e.g. "Wed, Sep 3". */
    private static final String DATE_SKELETON = "EEEMMMd";
    private static final long MINUTE_MS = 60_000L;

    // Date-row indicator contract, shared verbatim with FundamentalIntelligence's smartspace
    // service (the alarm target is identified by FEATURE_UPCOMING_ALARM instead of this extra).
    private static final String INDICATOR_EXTRA = "org.fundamentalos.smartspace.indicator";
    private static final String INDICATOR_DND = "dnd";
    // Gap between the row chips (DnD glyph, date, weather) and before the alarm chip.
    private static final float INDICATOR_GAP_DP = 8f;

    /** First line: [DnD] date weather, always laid out horizontally. */
    private final LinearLayout mRow;
    private final TextView mDateView;

    @Nullable private ImageView mDndView;
    @Nullable private TextView mAlarmView;
    @Nullable private BcSmartspaceDataPlugin mDataProvider;

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

        mRow = new LinearLayout(context);
        mRow.setOrientation(HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);

        mDateView = new TextView(context);
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mDateView.setSingleLine(true);
        mDateView.setTextColor(mPrimaryTextColor);
        applyTextStyle(mDateView);
        mRow.addView(mDateView,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // mRow is the outer view's first child. SmartspaceSection then "inserts" the weather view
        // into this view; addView() redirects it into mRow (right after the date). The alarm chip
        // is added later as the outer view's second child.
        super.addView(mRow,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /**
     * Give a smartspace text row its shared look: medium weight plus a soft dark shadow so it stays
     * legible over a bright wallpaper. The stock Google view used a DoubleShadowTextView.
     */
    static void applyTextStyle(TextView view) {
        // Match the lock-screen clock: Google Sans Flex (registered as the "google-sans" family),
        // medium weight.
        Typeface googleSans = Typeface.create("google-sans", Typeface.NORMAL);
        view.setTypeface(Typeface.create(googleSans, 500, false));
        float density = view.getResources().getDisplayMetrics().density;
        view.setShadowLayer(2f * density, 0f, density, 0xB2000000);
    }

    /**
     * SmartspaceSection appends the weather view to the date view expecting it to sit right after
     * the date. Redirect anything that is not one of our own structural children (the row itself or
     * the alarm line) into the horizontal row, so [DnD] date weather stay on a single line.
     */
    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (mRow != null && child != mRow && child != mAlarmView) {
            LayoutParams lp = new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(gapPx()); // gap between the date and the weather chip
            mRow.addView(child, lp);
            return;
        }
        super.addView(child, index, params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTicking();
        onTimeChanged();
        if (mDataProvider != null) {
            mDataProvider.registerListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTicking();
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
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
            mAlarmView.setCompoundDrawablePadding(gapPx() / 2);
            applyTextStyle(mAlarmView);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            // Outer view's second child: inline after the row when horizontal, its own line when
            // the section stacks the view vertically.
            super.addView(mAlarmView, lp);
            applyAlarmSpacing();
        }
        if (image != null) {
            image.setTint(mPrimaryTextColor);
            final int sz = indicatorIconPx();
            image.setBounds(0, 0, sz, sz);
        }
        mAlarmView.setText(description);
        mAlarmView.setContentDescription(description);
        mAlarmView.setCompoundDrawablesRelative(image, null, null, null);
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
        final int sz = indicatorIconPx();
        if (mDndView == null) {
            mDndView = new ImageView(getContext());
            LayoutParams lp = new LayoutParams(sz, sz);
            lp.setMarginEnd(gapPx()); // gap between the glyph and the date
            mRow.addView(mDndView, 0, lp); // leftmost, before the date
        } else {
            LayoutParams lp = (LayoutParams) mDndView.getLayoutParams();
            lp.width = sz;
            lp.height = sz;
            lp.setMarginEnd(gapPx());
            mDndView.setLayoutParams(lp);
        }
        image.setTint(mPrimaryTextColor);
        mDndView.setImageDrawable(image);
        mDndView.setContentDescription(description);
        mDndView.setVisibility(VISIBLE);
    }

    @Override
    public void registerDataProvider(BcSmartspaceDataPlugin plugin) {
        // The date text is self-driven; targets only carry the alarm / DnD row indicators.
        mDataProvider = plugin;
        if (isAttachedToWindow() && plugin != null) {
            plugin.registerListener(this);
        }
    }

    @Override
    public void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets) {
        SmartspaceTarget alarm = null;
        SmartspaceTarget dnd = null;
        for (Parcelable p : targets) {
            if (!(p instanceof SmartspaceTarget)) {
                continue;
            }
            SmartspaceTarget t = (SmartspaceTarget) p;
            if (t.getFeatureType() == SmartspaceTarget.FEATURE_UPCOMING_ALARM) {
                alarm = t;
            } else if (isDndTarget(t)) {
                dnd = t;
            }
        }
        applyAlarm(alarm);
        applyDnd(dnd);
    }

    private static boolean isDndTarget(SmartspaceTarget t) {
        SmartspaceAction header = t.getHeaderAction();
        Bundle extras = (header != null) ? header.getExtras() : null;
        return extras != null && INDICATOR_DND.equals(extras.getString(INDICATOR_EXTRA));
    }

    private void applyAlarm(@Nullable SmartspaceTarget target) {
        SmartspaceAction header = (target != null) ? target.getHeaderAction() : null;
        if (header == null) {
            setNextAlarm(null, null);
            return;
        }
        CharSequence title = header.getTitle();
        setNextAlarm(loadIcon(header.getIcon()), title != null ? title.toString() : null);
    }

    private void applyDnd(@Nullable SmartspaceTarget target) {
        SmartspaceAction header = (target != null) ? target.getHeaderAction() : null;
        if (header == null) {
            setDnd(null, null);
            return;
        }
        CharSequence desc = header.getContentDescription();
        setDnd(loadIcon(header.getIcon()), desc != null ? desc.toString() : null);
    }

    @Nullable
    private Drawable loadIcon(@Nullable Icon icon) {
        return icon != null ? icon.loadDrawable(getContext()) : null;
    }

    private int gapPx() {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDICATOR_GAP_DP,
                getResources().getDisplayMetrics());
    }

    /** Indicator glyphs match the date text height so they sit inline, not oversized. */
    private int indicatorIconPx() {
        return Math.round(mDateView.getTextSize() * 1.15f);
    }

    @Override
    public void setOrientation(int orientation) {
        super.setOrientation(orientation);
        applyAlarmSpacing();
    }

    /**
     * The section lays this view out horizontally when there is room for everything on one line
     * (small clock: the alarm sits inline after the row) and vertically beside a large clock (the
     * alarm drops onto its own line). Give the alarm a leading gap in a row and a small top gap on
     * its own line.
     */
    private void applyAlarmSpacing() {
        if (mAlarmView == null || !(mAlarmView.getLayoutParams() instanceof LayoutParams)) {
            return;
        }
        LayoutParams lp = (LayoutParams) mAlarmView.getLayoutParams();
        boolean horizontal = getOrientation() == HORIZONTAL;
        lp.setMarginStart(horizontal ? gapPx() : 0);
        lp.topMargin = horizontal ? 0 : gapPx() / 2;
        mAlarmView.setLayoutParams(lp);
    }

    @Override
    public void setPrimaryTextColor(int color) {
        mPrimaryTextColor = color;
        mDateView.setTextColor(color);
        if (mAlarmView != null) {
            mAlarmView.setTextColor(color);
            Drawable alarmIcon = mAlarmView.getCompoundDrawablesRelative()[0];
            if (alarmIcon != null) {
                alarmIcon.setTint(color);
            }
        }
        if (mDndView != null && mDndView.getDrawable() != null) {
            mDndView.getDrawable().setTint(color);
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
