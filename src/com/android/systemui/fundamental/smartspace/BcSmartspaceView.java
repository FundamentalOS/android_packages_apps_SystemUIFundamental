/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 FundamentalOS
 *
 * Lean stand-in for the Google carousel view
 * com.google.android.systemui.smartspace.BcSmartspaceView. The Google view is a ViewPager2/
 * RecyclerView carousel (BcSmartspaceView + CardPagerAdapter + CardRecyclerViewAdapter +
 * ~15 BcSmartspaceCard* subclasses + uitemplate/* + logging/* + generated proto/statslog) and
 * cannot be assembled from the available refs. This implementation is a single-card
 * {@link BcSmartspaceDataPlugin.SmartspaceView} that renders the primary target as stock's
 * at-a-glance card does -- a leading icon beside a two-line title/subtitle column -- and launches
 * its tap action, which is enough to satisfy the keyguard SmartspaceSection general view (and
 * KeyguardUnlockAnimationController, which reads getSelectedPage()/getCurrentCardTopPadding()).
 */
package com.android.systemui.fundamental.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import java.util.List;

public class BcSmartspaceView extends LinearLayout
        implements BcSmartspaceDataPlugin.SmartspaceView,
                BcSmartspaceDataPlugin.SmartspaceTargetListener {

    private final ImageView mIconView;
    private final TextView mTitleView;
    private final TextView mSubtitleView;

    private BcSmartspaceDataPlugin mDataProvider;
    private String mUiSurface;
    private float mDozeAmount;
    private int mPrimaryTextColor = Color.WHITE;

    public BcSmartspaceView(Context context) {
        this(context, null);
    }

    public BcSmartspaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // A leading icon, then a two-line title/subtitle column, centered against the icon.
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        final int gap = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f,
                context.getResources().getDisplayMetrics());
        final int iconSize = getResources().getDimensionPixelSize(
                R.dimen.fundamental_smartspace_icon_size);

        mIconView = new ImageView(context);
        mIconView.setVisibility(GONE);
        LayoutParams iconLp = new LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(gap);
        addView(mIconView, iconLp);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(VERTICAL);

        mTitleView = new TextView(context);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mTitleView.setSingleLine(true);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mTitleView.setTextColor(mPrimaryTextColor);
        DateSmartspaceView.applyTextStyle(mTitleView);
        column.addView(mTitleView,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        mSubtitleView = new TextView(context);
        mSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mSubtitleView.setSingleLine(true);
        mSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
        mSubtitleView.setTextColor(mPrimaryTextColor);
        mSubtitleView.setAlpha(0.7f);
        DateSmartspaceView.applyTextStyle(mSubtitleView);
        mSubtitleView.setVisibility(GONE);
        column.addView(mSubtitleView,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(column, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

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
            if (p instanceof SmartspaceTarget) {
                target = (SmartspaceTarget) p;
                break;
            }
        }
        final SmartspaceAction header = (target != null) ? target.getHeaderAction() : null;
        final CharSequence title = (header != null) ? header.getTitle() : null;
        if (TextUtils.isEmpty(title)) {
            setVisibility(GONE);
            setOnClickListener(null);
            return;
        }
        mTitleView.setText(title);
        mTitleView.setContentDescription(
                header.getContentDescription() != null ? header.getContentDescription() : title);

        // Leading icon, sized like the stock smartspace icon (glyphs may arrive full-size).
        Drawable iconDrawable = null;
        final Icon icon = header.getIcon();
        if (icon != null) {
            iconDrawable = icon.loadDrawable(getContext());
        }
        if (iconDrawable != null) {
            mIconView.setImageDrawable(iconDrawable);
            mIconView.setVisibility(VISIBLE);
        } else {
            mIconView.setImageDrawable(null);
            mIconView.setVisibility(GONE);
        }

        // Second line (e.g. a calendar event's time); hidden when the target carries none.
        final CharSequence subtitle = header.getSubtitle();
        if (TextUtils.isEmpty(subtitle)) {
            mSubtitleView.setVisibility(GONE);
        } else {
            mSubtitleView.setText(subtitle);
            mSubtitleView.setContentDescription(subtitle);
            mSubtitleView.setVisibility(VISIBLE);
        }

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
    public void registerConfigProvider(BcSmartspaceConfigPlugin configProvider) {
        // Still called by LockscreenSmartspaceController for the general view (the interface
        // default throws). Carousel/ViewPager2 toggles do not apply to this single-card view.
    }

    @Override
    public void setPrimaryTextColor(int color) {
        mPrimaryTextColor = color;
        mTitleView.setTextColor(color);
        mSubtitleView.setTextColor(color);
    }

    @Override
    public void setUiSurface(String uiSurface) {
        mUiSurface = uiSurface;
    }

    @Override
    public void setBgHandler(Handler bgHandler) {
        // No binder calls are made off this view.
    }

    @Override
    public void setDozeAmount(float amount) {
        mDozeAmount = amount;
    }

    @Override
    public void setFalsingManager(FalsingManager falsingManager) {
        // Taps are routed through the plugin's IntentStarter, which the controller guards.
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
