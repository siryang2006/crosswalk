// Copyright 2015 The Chromium Authors. All rights reserved.
// Copyright (c) 2016 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.tenta.xwalk.refactor;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

import com.tenta.chromium.cache.MetaCacheBackend;

import org.chromium.base.ThreadUtils;
import org.chromium.base.TraceEvent;
import org.chromium.base.UserData;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.metrics.RecordUserAction;
import org.chromium.base.task.PostTask;
import org.chromium.chrome.browser.gesturenav.NavigationGlowFactory;
import org.chromium.chrome.browser.gesturenav.NavigationHandler;
import org.chromium.chrome.browser.gesturenav.TabbedActionDelegate;
import org.chromium.content.browser.webcontents.WebContentsImpl;
import org.chromium.content_public.browser.UiThreadTaskTraits;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.OverscrollAction;
import org.chromium.ui.OverscrollRefreshHandler;
import org.chromium.third_party.android.swiperefresh.SwipeRefreshLayout;

/**
 * An overscroll handler implemented in terms a modified version of the Android compat library's
 * SwipeRefreshLayout effect.
 */
public class SwipeRefreshHandler implements OverscrollRefreshHandler, UserData {
    public static final Class<SwipeRefreshHandler> USER_DATA_KEY = SwipeRefreshHandler.class;
    
    // Synthetic delay between the {@link #didStopRefreshing()} signal and the
    // call to stop the refresh animation.
    private static final int STOP_REFRESH_ANIMATION_DELAY_MS = 500;

    // Max allowed duration of the refresh animation after a refresh signal,
    // guarding against cases where the page reload fails or takes too long.
    private static final int MAX_REFRESH_ANIMATION_DURATION_MS = 7500;

    // The modified AppCompat version of the refresh effect, handling all core
    // logic, rendering and animation.
    private final SwipeRefreshLayout mSwipeRefreshLayout;

    // The ContentViewCore with which the handler is associated. The handler
    // will set/unset itself as the default OverscrollRefreshHandler as the
    // association changes.
    private ContentViewCore mContentViewCore;
    
    // The container view the SwipeRefreshHandler instance is currently
    // associated with.
    private ViewGroup mContainerView;
    
    // Async runnable for ending the refresh animation after the page first
    // loads a frame. This is used to provide a reasonable minimum animation time.
    private Runnable mStopRefreshingRunnable;

    // Handles removing the layout from the view hierarchy.  This is posted to ensure it does not
    // conflict with pending Android draws.
    private Runnable mDetachRefreshLayoutRunnable;
    
    // Accessibility utterance used to indicate refresh activation.
    private String mAccessibilityRefreshString;

    private @OverscrollAction int mSwipeType;
    
    /**
     * Simple constructor to use when creating an OverscrollRefresh instance from code.
     *
     * @param context The associated context.
     */
    public SwipeRefreshHandler(Context context) {
        WebContentsImpl
        mSwipeRefreshLayout = new SwipeRefreshLayout(context);
        mSwipeRefreshLayout.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mSwipeRefreshLayout.setColorSchemeResources(R.color.xwalk_light_active_color);
        // SwipeRefreshLayout.LARGE layouts appear broken on JellyBean.
        mSwipeRefreshLayout.setSize(SwipeRefreshLayout.DEFAULT);
        mSwipeRefreshLayout.setEnabled(false);
    }

    /**
     * Pair the effect with a given ContentViewCore instance. If that instance is null, the effect
     * will be disabled.
     * 
     * @param contentViewCore The associated ContentViewCore instance.
     */
    public void setContentViewCore(final ContentViewCore contentViewCore) {
        if (mContentViewCore == contentViewCore)
            return;

        if (mContentViewCore != null) {
            setEnabled(false);
            cancelStopRefreshingRunnable();
            mSwipeRefreshLayout.setOnRefreshListener(null);
            mContentViewCore.getWebContents().setOverscrollRefreshHandler(null);
        }

        mContentViewCore = contentViewCore;

        if (mContentViewCore == null)
            return;

        setEnabled(true);
        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                cancelStopRefreshingRunnable();
                mSwipeRefreshLayout.postDelayed(
                        getStopRefreshingRunnable(), MAX_REFRESH_ANIMATION_DURATION_MS);
                // TODO (iotto) see where the string resources are gone
                if (mAccessibilityRefreshString == null) {
                    int resId = R.string.xwalk_accessibility_swipe_refresh;
                    mAccessibilityRefreshString = contentViewCore.getContext().getResources()
                            .getString(resId);
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                    mSwipeRefreshLayout.announceForAccessibility(mAccessibilityRefreshString);
                }
                new AsyncTask<Void, Void, Void>() {
                    protected void onPreExecute() {
                        contentViewCore.getWebContents().stop();
                    };

                    @Override
                    protected Void doInBackground(Void... params) {
                        MetaCacheBackend.clearActiveTabCache();
                        return null;
                    }

                    protected void onPostExecute(Void result) {
                        if (contentViewCore != null && contentViewCore.getWebContents() != null &&
                                contentViewCore.getWebContents()
                                        .getNavigationController() != null) {
                            // contentViewCore.getWebContents().getNavigationController().reloadBypassingCache(true);
                            contentViewCore.getWebContents().getNavigationController()
                                    .reloadBypassingCache(true);
                            // .reloadToRefreshContent(true);
                        }
                    };

                }.execute();

                RecordUserAction.record("MobilePullGestureReload");
            }
        });
        mSwipeRefreshLayout.setOnResetListener(() -> {
            if (mDetachRefreshLayoutRunnable != null) return;
            mDetachRefreshLayoutRunnable = () -> {
                mDetachRefreshLayoutRunnable = null;
                detachSwipeRefreshLayoutIfNecessary();
            };
            PostTask.postTask(UiThreadTaskTraits.DEFAULT, mDetachRefreshLayoutRunnable);
        });
        contentViewCore.getWebContents().setOverscrollRefreshHandler(this);
    }

    /**
     * Notify the SwipeRefreshLayout that a refresh action has completed. Defer the notification by
     * a reasonable minimum to ensure sufficient visiblity of the animation.
     */
    public void didStopRefreshing() {
        if (!mSwipeRefreshLayout.isRefreshing())
            return;
        cancelStopRefreshingRunnable();
        mSwipeRefreshLayout.postDelayed(
                getStopRefreshingRunnable(), STOP_REFRESH_ANIMATION_DELAY_MS);
    }

    @Override
    public boolean start() {
        attachSwipeRefreshLayoutIfNecessary();
        return mSwipeRefreshLayout.start();
    }

    @Override
    public void pull(float delta) {
        TraceEvent.begin("SwipeRefreshHandler.pull");
        mSwipeRefreshLayout.pull(delta);
        TraceEvent.end("SwipeRefreshHandler.pull");
    }

    @Override
    public void release(boolean allowRefresh) {
        TraceEvent.begin("SwipeRefreshHandler.release");
        mSwipeRefreshLayout.release(allowRefresh);
        TraceEvent.end("SwipeRefreshHandler.release");
    }

    @Override
    public void reset() {
        cancelStopRefreshingRunnable();
        mSwipeRefreshLayout.reset();
        detachSwipeRefreshLayoutIfNecessary();
    }

    @Override
    public void setEnabled(boolean enabled) {
        mSwipeRefreshLayout.setEnabled(enabled);
        if (!enabled)
            reset();
    }

    private void cancelStopRefreshingRunnable() {
        if (mStopRefreshingRunnable != null) {
            mSwipeRefreshLayout.removeCallbacks(mStopRefreshingRunnable);
        }
    }

    private Runnable getStopRefreshingRunnable() {
        if (mStopRefreshingRunnable == null) {
            mStopRefreshingRunnable = new Runnable() {
                @Override
                public void run() {
                    mSwipeRefreshLayout.setRefreshing(false);
                }
            };
        }
        return mStopRefreshingRunnable;
    }

    // The animation view is attached/detached on-demand to minimize overlap
    // with composited SurfaceView content.
    private void attachSwipeRefreshLayoutIfNecessary() {
        cancelDetachLayoutRunnable();
        if (mSwipeRefreshLayout.getParent() == null) {
            mContainerView.addView(mSwipeRefreshLayout);
        }
//        
//        if (mContentViewCore == null)
//            return;
//        if (mSwipeRefreshLayout.getParent() == null) {
//            mContentViewCore.getContainerView().addView(mSwipeRefreshLayout);
//        }
    }

    private void detachSwipeRefreshLayoutIfNecessary() {
        cancelDetachLayoutRunnable();
        if (mSwipeRefreshLayout.getParent() != null) {
            mContainerView.removeView(mSwipeRefreshLayout);
        }
//        
//        // TODO(jdduke): Also detach the effect when its animation ends.
//        if (mContentViewCore == null)
//            return;
//        if (mSwipeRefreshLayout.getParent() != null) {
//            mContentViewCore.getContainerView().removeView(mSwipeRefreshLayout);
//        }
    }

    private void cancelDetachLayoutRunnable() {
        if (mDetachRefreshLayoutRunnable != null) {
            ThreadUtils.getUiThreadHandler().removeCallbacks(mDetachRefreshLayoutRunnable);
            mDetachRefreshLayoutRunnable = null;
        }
    }
    
    @Override
	public boolean start(int type, float startX, float startY, boolean navigateForward) {
    	mSwipeType = type;
    	
    	if (type == OverscrollAction.PULL_TO_REFRESH) {
            if (mSwipeRefreshLayout == null) initSwipeRefreshLayout(mTab.getContext());
            attachSwipeRefreshLayoutIfNecessary();
            return mSwipeRefreshLayout.start();
        } 
//    	else if (type == OverscrollAction.HISTORY_NAVIGATION && mNavigationEnabled) {
//            if (mNavigationHandler == null) {
//                mNavigationHandler =
//                        new NavigationHandler(mContainerView, new TabbedActionDelegate(mTab),
//                                NavigationGlowFactory.forRenderedPage(
//                                        mContainerView, mTab.getWebContents()));
//            }
//            boolean navigable = navigateForward ? mTab.canGoForward() : mTab.canGoBack();
//            boolean showGlow = navigateForward && !mTab.canGoForward();
//            mNavigationHandler.onDown(); // Simulates the initial onDown event.
//            if (navigable) {
//                mNavigationHandler.showArrowWidget(navigateForward);
//            } else if (showGlow) {
//                mNavigationHandler.showGlow(startX, startY);
//            }
//            return navigable || showGlow;
//        }
        mSwipeType = OverscrollAction.NONE;
        return false;
	}

	public void pull(float xDelta, float yDelta) {
		// TODO Auto-generated method stub
		
	}
}
