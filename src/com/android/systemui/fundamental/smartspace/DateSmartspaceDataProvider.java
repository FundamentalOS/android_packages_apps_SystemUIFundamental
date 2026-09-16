/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2026 FundamentalOS
 *
 * Ported from Google SystemUI com.google.android.systemui.smartspace.DateSmartspaceDataProvider.
 * The date text is self-driven (a clock), but the plugin still fans out smartspace targets so the
 * view can render the date-row indicators (the next alarm and Do Not Disturb). getView() /
 * getLargeClockView() return a lean {@link DateSmartspaceView} instead of inflating
 * date_plus_extras(_large).xml.
 */
package com.android.systemui.fundamental.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.view.View;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Standalone date (+ alarm + DND) lockscreen smartspace plugin. */
public final class DateSmartspaceDataProvider implements BcSmartspaceDataPlugin {

    private final Set<SmartspaceTargetListener> mSmartspaceTargetListeners =
            new CopyOnWriteArraySet<>();
    private final Set<View> mViews = new HashSet<>();
    private final Set<View.OnAttachStateChangeListener> mAttachListeners = new HashSet<>();
    private final EventNotifierProxy mEventNotifier = new EventNotifierProxy();

    private List<SmartspaceTarget> mSmartspaceTargets = Collections.emptyList();

    private final View.OnAttachStateChangeListener mStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mViews.add(v);
                    for (View.OnAttachStateChangeListener l : mAttachListeners) {
                        l.onViewAttachedToWindow(v);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mViews.remove(v);
                    for (View.OnAttachStateChangeListener l : mAttachListeners) {
                        l.onViewDetachedFromWindow(v);
                    }
                }
            };

    @Override
    public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
        mAttachListeners.add(listener);
        for (View v : mViews) {
            listener.onViewAttachedToWindow(v);
        }
    }

    @Override
    public SmartspaceEventNotifier getEventNotifier() {
        return mEventNotifier;
    }

    @Override
    public SmartspaceView getView(Context context) {
        DateSmartspaceView view = new DateSmartspaceView(context);
        // Shared id the keyguard SmartspaceSection constrains the small-clock date row by.
        view.setId(com.android.systemui.shared.R.id.date_smartspace_view);
        view.addOnAttachStateChangeListener(mStateChangeListener);
        return view;
    }

    @Override
    public SmartspaceView getLargeClockView(Context context) {
        DateSmartspaceView view = new DateSmartspaceView(context);
        view.setId(com.android.systemui.shared.R.id.date_smartspace_view_large);
        view.addOnAttachStateChangeListener(mStateChangeListener);
        return view;
    }

    @Override
    public void onTargetsAvailable(List<SmartspaceTarget> targets) {
        mSmartspaceTargets = targets;
        for (SmartspaceTargetListener listener : mSmartspaceTargetListeners) {
            listener.onSmartspaceTargetsUpdated(mSmartspaceTargets);
        }
    }

    @Override
    public void registerListener(SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.add(listener);
        listener.onSmartspaceTargetsUpdated(mSmartspaceTargets);
    }

    @Override
    public void unregisterListener(SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.remove(listener);
    }

    @Override
    public void setEventDispatcher(SmartspaceEventDispatcher eventDispatcher) {
        mEventNotifier.setEventDispatcher(eventDispatcher);
    }

    @Override
    public void setIntentStarter(IntentStarter intentStarter) {
        mEventNotifier.setIntentStarter(intentStarter);
    }
}
