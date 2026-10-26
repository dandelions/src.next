// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.ui.appmenu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.View;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.chromium.base.ContextUtils;
import org.chromium.chrome.browser.night_mode.ThemeType;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.ui.modelutil.MVCListAdapter.ListItem;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.chromium.base.Callback;
import org.chromium.base.metrics.RecordUserAction;
import org.chromium.base.supplier.Supplier;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.lifecycle.ConfigurationChangedObserver;
import org.chromium.chrome.browser.lifecycle.StartStopWithNativeObserver;
import org.chromium.chrome.browser.ui.appmenu.internal.R;
import org.chromium.components.browser_ui.widget.textbubble.TextBubble;
import org.chromium.ui.display.DisplayAndroidManager;
import org.chromium.ui.modelutil.LayoutViewBuilder;
import org.chromium.ui.modelutil.MVCListAdapter.ModelList;
import org.chromium.ui.modelutil.ModelListAdapter;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.chromium.base.Log;

/**
 * Object responsible for handling the creation, showing, hiding of the AppMenu and notifying the
 * AppMenuObservers about these actions.
 */
class AppMenuHandlerImpl
        implements AppMenuHandler, StartStopWithNativeObserver, ConfigurationChangedObserver {
    private AppMenu mAppMenu;
    private AppMenuDragHelper mAppMenuDragHelper;
    private final List<AppMenuBlocker> mBlockers;
    private final List<AppMenuObserver> mObservers;
    private final View mHardwareButtonMenuAnchor;

    private final Context mContext;
    private final AppMenuPropertiesDelegate mDelegate;
    private final AppMenuDelegate mAppMenuDelegate;
    private final View mDecorView;
    private final ActivityLifecycleDispatcher mActivityLifecycleDispatcher;
    private final Supplier<Rect> mAppRect;

    private Callback<Integer> mTestOptionsItemSelectedListener;

    private ModelList mModelList;
    // Lemur style bottom sheet app menu state.
    private static @Nullable Boolean sLemurMenuEnabledForTesting;
    private @Nullable Dialog mLemurDialog;
    private boolean mLemurSuppressDismissCallback;

    /**
     * The resource id of the menu item to highlight when the menu next opens. A value of
     * {@code null} means no item will be highlighted.  This value will be cleared after the menu is
     * opened.
     */
    private Integer mHighlightMenuId;

    /**
     * Constructs an AppMenuHandlerImpl object.
     * @param context The activity context.
     * @param delegate Delegate used to check the desired AppMenu properties on show.
     * @param appMenuDelegate The AppMenuDelegate to handle menu item selection.
     * @param menuResourceId Resource Id that should be used as the source for the menu items.
     *            It is assumed to have back_menu_id, forward_menu_id, bookmark_this_page_id.
     * @param decorView The decor {@link View}, e.g. from Window#getDecorView(), for the containing
     *            activity.
     * @param activityLifecycleDispatcher The {@link ActivityLifecycleDispatcher} for the containing
     *            activity.
     * @param hardwareButtonAnchorView The {@link View} used as an anchor for the menu when it is
     *            displayed using a hardware button.
     * @param appRect Supplier of the app area in Window that the menu should fit in.
     */
    public AppMenuHandlerImpl(Context context, AppMenuPropertiesDelegate delegate,
            AppMenuDelegate appMenuDelegate, View decorView,
            ActivityLifecycleDispatcher activityLifecycleDispatcher, View hardwareButtonAnchorView,
            Supplier<Rect> appRect) {
        mContext = context;
        mAppMenuDelegate = appMenuDelegate;
        mDelegate = delegate;
        mDecorView = decorView;
        mBlockers = new ArrayList<>();
        mObservers = new ArrayList<>();
        mHardwareButtonMenuAnchor = hardwareButtonAnchorView;
        mAppRect = appRect;

        mActivityLifecycleDispatcher = activityLifecycleDispatcher;
        mActivityLifecycleDispatcher.register(this);

        assert mHardwareButtonMenuAnchor
                != null : "Using AppMenu requires to have menu_anchor_stub view";
    }

    /**
     * Called when the containing activity is being destroyed.
     */
    void destroy() {
        // Prevent the menu window from leaking.
        hideAppMenu();

        mActivityLifecycleDispatcher.unregister(this);
    }

    @Override
    public void menuItemContentChanged(int menuRowId) {
        if (mAppMenu != null) mAppMenu.menuItemContentChanged(menuRowId);
    }

    @Override
    public void clearMenuHighlight() {
        setMenuHighlight(null);
    }

    @Override
    public void setMenuHighlight(Integer highlightItemId) {
        if (mHighlightMenuId == null && highlightItemId == null) return;
        if (mHighlightMenuId != null && mHighlightMenuId.equals(highlightItemId)) return;
        mHighlightMenuId = highlightItemId;
        boolean highlighting = mHighlightMenuId != null;
        for (AppMenuObserver observer : mObservers) observer.onMenuHighlightChanged(highlighting);
    }

    /**
     * Show the app menu.
     * @param anchorView    Anchor view (usually a menu button) to be used for the popup, if null is
     *                      passed then hardware menu button anchor will be used.
     * @param startDragging Whether dragging is started. For example, if the app menu is showed by
     *                      tapping on a button, this should be false. If it is showed by start
     *                      dragging down on the menu button, this should be true. Note that if
     *                      anchorView is null, this must be false since we no longer support
     *                      hardware menu button dragging.
     * @return              True, if the menu is shown, false, if menu is not shown, example
     *                      reasons: the menu is not yet available to be shown, or the menu is
     *                      already showing.
     */
    // TODO(crbug.com/635567): Fix this properly.
    @SuppressLint("ResourceType")
    boolean showAppMenu(View anchorView, boolean startDragging) {
        if (!shouldShowAppMenu() || isAppMenuShowing()) return false;

        TextBubble.dismissBubbles();
        boolean isByPermanentButton = false;

        Display display = DisplayAndroidManager.getDefaultDisplayForContext(mContext);
        int rotation = display.getRotation();
        if (anchorView == null) {
            // This fixes the bug where the bottom of the menu starts at the top of
            // the keyboard, instead of overlapping the keyboard as it should.
            int displayHeight = mContext.getResources().getDisplayMetrics().heightPixels;
            Rect rect = new Rect();
            mDecorView.getWindowVisibleDisplayFrame(rect);
            int statusBarHeight = rect.top;
            mHardwareButtonMenuAnchor.setY((displayHeight - statusBarHeight));

            anchorView = mHardwareButtonMenuAnchor;
            isByPermanentButton = true;
        }

        // If the anchor view used to show the popup or the activity's decor view is not attached
        // to window, we don't show the app menu because the window manager might have revoked
        // the window token for this activity. See https://crbug.com/1105831.
        if (!mDecorView.isAttachedToWindow() || !anchorView.isAttachedToWindow()
                || !anchorView.getRootView().isAttachedToWindow()) {
            return false;
        }

        assert !(isByPermanentButton && startDragging);

        List<CustomViewBinder> customViewBinders = mDelegate.getCustomViewBinders();
        Map<CustomViewBinder, Integer> customViewTypeOffsetMap =
                populateCustomViewBinderOffsetMap(customViewBinders, AppMenuItemType.NUM_ENTRIES);
        ModelList modelList = mDelegate.getMenuItems(((id) -> {
            return getCustomItemViewType(id, customViewBinders, customViewTypeOffsetMap);
        }),
                this);
        mModelList = modelList;

        // Lemur style: present the menu as a rounded bottom sheet. The sheet is
        // generated from the PropertyModels above, so it stays in sync with
        // every menu Kiwi builds (page / overview / tablet empty).
        if (!startDragging && showLemurMenu(mModelList)) {
            clearMenuHighlight();
            RecordUserAction.record("MobileMenuShow");
            onMenuVisibilityChanged(true);
            return true;
        }

        ContextThemeWrapper wrapper =
                new ContextThemeWrapper(mContext, R.style.OverflowMenuThemeOverlay);

        if (mAppMenu == null) {
            TypedArray a = wrapper.obtainStyledAttributes(
                    new int[] {android.R.attr.listPreferredItemHeightSmall});
            int itemRowHeight = a.getDimensionPixelSize(0, 0);
            a.recycle();
            mAppMenu = new AppMenu(itemRowHeight, this, mContext.getResources());
            mAppMenuDragHelper = new AppMenuDragHelper(mContext, mAppMenu, itemRowHeight);
        }
        setupModelForHighlightAndClick(modelList, mHighlightMenuId, mAppMenu);
        ModelListAdapter adapter = new ModelListAdapter(modelList);
        mAppMenu.updateMenu(modelList, adapter);
        registerViewBinders(customViewBinders, customViewTypeOffsetMap, adapter,
                mDelegate.shouldShowIconBeforeItem());

        Rect appRect = mAppRect.get();

        // Use full size of window for abnormal appRect.
        if (appRect.left < 0 && appRect.top < 0) {
            appRect.left = 0;
            appRect.top = 0;
            appRect.right = mDecorView.getWidth();
            appRect.bottom = mDecorView.getHeight();
        }

        Point pt = new Point();
        display.getSize(pt);

        int footerResourceId = 0;
        if (mDelegate.shouldShowFooter(appRect.height())) {
            footerResourceId = mDelegate.getFooterResourceId();
        }
        int headerResourceId = 0;
        if (mDelegate.shouldShowHeader(appRect.height())) {
            headerResourceId = mDelegate.getHeaderResourceId();
        }
        mAppMenu.show(wrapper, anchorView, isByPermanentButton, rotation, appRect, pt.y,
                footerResourceId, headerResourceId, mDelegate.getGroupDividerId(), mHighlightMenuId,
                customViewBinders, mDelegate.isMenuIconAtStart());
        mAppMenuDragHelper.onShow(startDragging);
        clearMenuHighlight();
        RecordUserAction.record("MobileMenuShow");
        return true;
    }

    // ---------------------------------------------------------------------
    // Lemur style app menu (rounded bottom sheet). Ported from
    // android-helium-browser's patch_lemur_app_menu.py, adapted for the
    // Chromium 105 model-list based app menu used by Kiwi.
    // ---------------------------------------------------------------------
    private static final int LEMUR_EXTENSION_ID_BASE = 999999;
    private static final int LEMUR_GRID_COLUMNS = 5;
    private static final int LEMUR_EXTENSION_COLUMNS = 4;

    @VisibleForTesting
    static void setLemurMenuEnabledForTesting(@Nullable Boolean enabled) {
        sLemurMenuEnabledForTesting = enabled;
    }

    private boolean isLemurMenuEnabled() {
        return sLemurMenuEnabledForTesting == null || sLemurMenuEnabledForTesting;
    }

    private boolean isLemurMenuShowing() {
        return mLemurDialog != null && mLemurDialog.isShowing();
    }

    private void hideLemurMenu() {
        if (mLemurDialog == null) return;
        Dialog dialog = mLemurDialog;
        mLemurDialog = null;
        mLemurSuppressDismissCallback = true;
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } finally {
            mLemurSuppressDismissCallback = false;
        }
    }

    private void onLemurMenuDismissed() {
        if (mAppMenuDragHelper != null) mAppMenuDragHelper.finishDragging();
        mDelegate.onMenuDismissed();
        onMenuVisibilityChanged(false);
    }

    private void runLemurMenuAction(int itemId) {
        boolean wasShowing = isLemurMenuShowing();
        hideLemurMenu();
        if (wasShowing) {
            mDelegate.onMenuDismissed();
            onMenuVisibilityChanged(false);
        }
        onOptionsItemSelected(itemId, false);
    }

    private int lemurDp(float dp) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, dp, mContext.getResources().getDisplayMetrics()));
    }

    private boolean isLemurNightMode() {
        int savedTheme =
                SharedPreferencesManager.getInstance()
                        .readInt(ChromePreferenceKeys.UI_THEME_SETTING, ThemeType.SYSTEM_DEFAULT);
        if (savedTheme == ThemeType.DARK) return true;
        if (savedTheme == ThemeType.LIGHT) return false;
        return (mContext.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static String lemurLabel(@Nullable CharSequence text) {
        return text == null ? "" : text.toString();
    }

    private static boolean lemurIsEnabled(PropertyModel model) {
        Boolean enabled = model.get(AppMenuItemProperties.ENABLED);
        return enabled == null || enabled;
    }

    private static boolean lemurIsChecked(PropertyModel model) {
        Boolean checked = model.get(AppMenuItemProperties.CHECKED);
        return checked != null && checked;
    }

    private static boolean lemurIsIconRowType(int type) {
        return type == AppMenuItemType.THREE_BUTTON_ROW
                || type == AppMenuItemType.FOUR_BUTTON_ROW
                || type == AppMenuItemType.FIVE_BUTTON_ROW;
    }

    /** Colours for the sheet, mirroring Lemur Browser's light/dark palettes. */
    private static final class LemurPalette {
        int sheetBackground;
        int textPrimary;
        int textSecondary;
        int accent;
        int ripple;
        int divider;

        static LemurPalette create(boolean isNight) {
            LemurPalette palette = new LemurPalette();
            palette.sheetBackground = isNight ? 0xFF222327 : 0xFFF7F8FA;
            palette.textPrimary = isNight ? 0xFFF1F3F4 : 0xFF1F1F1F;
            palette.textSecondary = isNight ? 0xFF9AA0A6 : 0xFF5F6368;
            palette.accent = isNight ? 0xFF8AB4F8 : 0xFF1A73E8;
            palette.ripple = isNight ? 0x26FFFFFF : 0x18000000;
            palette.divider = isNight ? 0x1FFFFFFF : 0x14000000;
            return palette;
        }
    }

    /**
     * Builds and shows the rounded bottom sheet for the supplied menu models.
     *
     * @return Whether the sheet is now showing. {@code false} means the caller
     *         should fall back to the native popup menu.
     */
    private boolean showLemurMenu(@Nullable ModelList modelList) {
        if (!isLemurMenuEnabled() || modelList == null || modelList.size() == 0) return false;

        Activity activity = ContextUtils.activityFromContext(mContext);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        List<PropertyModel> gridModels = new ArrayList<>();
        List<PropertyModel> extensionModels = new ArrayList<>();
        List<PropertyModel> listModels = new ArrayList<>();
        for (int i = 0; i < modelList.size(); i++) {
            ListItem item = modelList.get(i);
            PropertyModel model = item.model;
            Integer itemId = model.get(AppMenuItemProperties.MENU_ITEM_ID);
            if (lemurIsIconRowType(item.type)) {
                ModelList submenu = model.get(AppMenuItemProperties.SUBMENU);
                if (submenu == null) continue;
                for (int j = 0; j < submenu.size(); j++) {
                    gridModels.add(submenu.get(j).model);
                }
                continue;
            }
            // Kiwi appends running extensions with synthetic ids; render those
            // as their own grid so their real icons stay visible.
            if (itemId != null && itemId >= LEMUR_EXTENSION_ID_BASE) {
                extensionModels.add(model);
                continue;
            }
            if (lemurLabel(model.get(AppMenuItemProperties.TITLE)).isEmpty()) continue;
            listModels.add(model);
        }
        if (gridModels.isEmpty() && extensionModels.isEmpty() && listModels.isEmpty()) {
            return false;
        }

        final LemurPalette palette = LemurPalette.create(isLemurNightMode());
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.35f;
            window.setAttributes(attributes);
        }
        mLemurDialog = dialog;
        mLemurSuppressDismissCallback = false;
        dialog.setOnDismissListener(
                d -> {
                    if (mLemurDialog == d) mLemurDialog = null;
                    if (!mLemurSuppressDismissCallback) onLemurMenuDismissed();
                });

        FrameLayout host = new FrameLayout(mContext);
        host.setClickable(true);
        host.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(mContext);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable sheetBackground = new GradientDrawable();
        sheetBackground.setShape(GradientDrawable.RECTANGLE);
        float topRadius = lemurDp(24);
        sheetBackground.setCornerRadii(
                new float[] {topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f});
        sheetBackground.setColor(palette.sheetBackground);
        sheet.setBackground(sheetBackground);
        sheet.setElevation(lemurDp(16));

        View handle = new View(mContext);
        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setCornerRadius(lemurDp(2));
        handleBackground.setColor(palette.divider);
        handle.setBackground(handleBackground);
        LinearLayout.LayoutParams handleParams =
                new LinearLayout.LayoutParams(lemurDp(36), lemurDp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = lemurDp(10);
        handleParams.bottomMargin = lemurDp(6);
        sheet.addView(handle, handleParams);

        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(lemurDp(10), 0, lemurDp(10), lemurDp(18));

        int contentHeightPx = lemurDp(20);
        int gridRowHeightPx = lemurDp(72);
        int listRowHeightPx = lemurDp(46);

        if (!gridModels.isEmpty()) {
            int gridRows = (gridModels.size() + LEMUR_GRID_COLUMNS - 1) / LEMUR_GRID_COLUMNS;
            LinearLayout section =
                    createLemurGridSection(gridModels, LEMUR_GRID_COLUMNS, palette, gridRowHeightPx);
            content.addView(
                    section,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, gridRows * gridRowHeightPx));
            contentHeightPx += gridRows * gridRowHeightPx;
        }

        if (!extensionModels.isEmpty()) {
            int extensionRows =
                    (extensionModels.size() + LEMUR_EXTENSION_COLUMNS - 1) / LEMUR_EXTENSION_COLUMNS;
            int labelHeightPx = lemurDp(28);
            content.addView(createLemurSectionLabel(LEMUR_SECTION_EXTENSIONS, palette));
            contentHeightPx += labelHeightPx;
            LinearLayout section =
                    createLemurGridSection(
                            extensionModels, LEMUR_EXTENSION_COLUMNS, palette, gridRowHeightPx);
            content.addView(
                    section,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, extensionRows * gridRowHeightPx));
            contentHeightPx += extensionRows * gridRowHeightPx;
        }

        if (!listModels.isEmpty()) {
            int labelHeightPx = lemurDp(28);
            content.addView(createLemurSectionLabel(LEMUR_SECTION_MORE, palette));
            contentHeightPx += labelHeightPx;
            LinearLayout section =
                    createLemurListSection(listModels, palette, listRowHeightPx);
            content.addView(
                    section,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            listModels.size() * listRowHeightPx));
            contentHeightPx += listModels.size() * listRowHeightPx;
        }

        ScrollView scrollView = new ScrollView(mContext);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(content);

        int screenHeightPx = mContext.getResources().getDisplayMetrics().heightPixels;
        int maxSheetHeightPx = (int) (screenHeightPx * 0.78f);
        int sheetHeightPx = Math.min(contentHeightPx, maxSheetHeightPx);
        sheet.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, sheetHeightPx));

        int screenWidthPx = mContext.getResources().getDisplayMetrics().widthPixels;
        int maxSheetWidthPx = lemurDp(520);
        int sheetWidthPx =
                screenWidthPx > maxSheetWidthPx
                        ? maxSheetWidthPx
                        : ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams sheetParams =
                new FrameLayout.LayoutParams(
                        sheetWidthPx,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        host.addView(sheet, sheetParams);

        dialog.setContentView(host);
        dialog.show();
        return true;
    }

    private LinearLayout createLemurGridSection(
            List<PropertyModel> models, int columns, LemurPalette palette, int rowHeightPx) {
        LinearLayout section = new LinearLayout(mContext);
        section.setOrientation(LinearLayout.VERTICAL);
        int rows = (models.size() + columns - 1) / columns;
        for (int row = 0; row < rows; row++) {
            LinearLayout rowView = new LinearLayout(mContext);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= models.size()) {
                    rowView.addView(
                            new View(mContext),
                            new LinearLayout.LayoutParams(0, rowHeightPx, 1f));
                    continue;
                }
                rowView.addView(
                        createLemurGridTile(models.get(index), palette),
                        new LinearLayout.LayoutParams(0, rowHeightPx, 1f));
            }
            section.addView(
                    rowView,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx));
        }
        return section;
    }

    private View createLemurGridTile(PropertyModel model, LemurPalette palette) {
        boolean enabled = lemurIsEnabled(model);
        boolean active = lemurIsChecked(model);
        int iconColor = active ? palette.accent : (enabled ? palette.textPrimary : palette.textSecondary);

        LinearLayout cell = new LinearLayout(mContext);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(lemurDp(2), lemurDp(10), lemurDp(2), lemurDp(8));
        GradientDrawable rippleMask = new GradientDrawable();
        rippleMask.setShape(GradientDrawable.RECTANGLE);
        rippleMask.setCornerRadius(lemurDp(12));
        rippleMask.setColor(Color.WHITE);
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, rippleMask));

        ImageView iconView = new ImageView(mContext);
        Drawable icon = model.get(AppMenuItemProperties.ICON);
        iconView.setImageDrawable(icon != null ? icon : createLemurPlaceholderIcon(iconColor));
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(lemurDp(24), lemurDp(24));
        iconParams.bottomMargin = lemurDp(6);
        cell.addView(iconView, iconParams);

        TextView labelView = new TextView(mContext);
        labelView.setText(lemurLabel(model.get(AppMenuItemProperties.TITLE)));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        labelView.setTextColor(
                active ? palette.accent : (enabled ? palette.textPrimary : palette.textSecondary));
        labelView.setGravity(Gravity.CENTER);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(labelView);

        if (enabled) {
            Integer itemId = model.get(AppMenuItemProperties.MENU_ITEM_ID);
            if (itemId != null) cell.setOnClickListener(v -> runLemurMenuAction(itemId));
        } else {
            cell.setAlpha(0.5f);
        }
        return cell;
    }

    private LinearLayout createLemurListSection(
            List<PropertyModel> models, LemurPalette palette, int rowHeightPx) {
        LinearLayout section = new LinearLayout(mContext);
        section.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < models.size(); i++) {
            section.addView(
                    createLemurListRow(models.get(i), palette),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx));
        }
        return section;
    }

    private View createLemurListRow(PropertyModel model, LemurPalette palette) {
        boolean enabled = lemurIsEnabled(model);
        boolean checked = lemurIsChecked(model);

        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(lemurDp(12), 0, lemurDp(12), 0);
        GradientDrawable rippleMask = new GradientDrawable();
        rippleMask.setCornerRadius(lemurDp(10));
        rippleMask.setColor(Color.WHITE);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.ripple), null, rippleMask));

        Drawable icon = model.get(AppMenuItemProperties.ICON);
        if (icon != null) {
            ImageView iconView = new ImageView(mContext);
            iconView.setImageDrawable(icon);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(lemurDp(20), lemurDp(20));
            iconParams.rightMargin = lemurDp(12);
            row.addView(iconView, iconParams);
        }

        TextView labelView = new TextView(mContext);
        labelView.setText(lemurLabel(model.get(AppMenuItemProperties.TITLE)));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        labelView.setTextColor(enabled ? palette.textPrimary : palette.textSecondary);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(
                labelView,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (checked) {
            TextView checkView = new TextView(mContext);
            checkView.setText("\u2713");
            checkView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            checkView.setTextColor(palette.accent);
            row.addView(checkView);
        }

        if (enabled) {
            Integer itemId = model.get(AppMenuItemProperties.MENU_ITEM_ID);
            if (itemId != null) row.setOnClickListener(v -> runLemurMenuAction(itemId));
        } else {
            row.setAlpha(0.5f);
        }
        return row;
    }

    private TextView createLemurSectionLabel(String text, LemurPalette palette) {
        TextView labelView = new TextView(mContext);
        labelView.setText(text);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setTextColor(palette.textSecondary);
        labelView.setPadding(lemurDp(12), lemurDp(12), lemurDp(12), lemurDp(4));
        return labelView;
    }

    private Drawable createLemurPlaceholderIcon(int color) {
        int sizePx = lemurDp(24);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(lemurDp(1.6f));
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 3f, paint);
        canvas.drawLine(sizePx * 0.34f, sizePx * 0.5f, sizePx * 0.66f, sizePx * 0.5f, paint);
        return new BitmapDrawable(mContext.getResources(), bitmap);
    }

    void appMenuDismissed() {
        mAppMenuDragHelper.finishDragging();
        mDelegate.onMenuDismissed();
    }

    @Override
    public boolean isAppMenuShowing() {
        return (mAppMenu != null && mAppMenu.isShowing()) || isLemurMenuShowing();
    }

    /**
     * @return The App Menu that the menu handler is interacting with.
     */
    public AppMenu getAppMenu() {
        return mAppMenu;
    }

    AppMenuDragHelper getAppMenuDragHelper() {
        return mAppMenuDragHelper;
    }

    @Override
    public void hideAppMenu() {
        hideLemurMenu();
        if (mAppMenu != null && mAppMenu.isShowing()) mAppMenu.dismiss();
    }

    @Override
    public AppMenuButtonHelper createAppMenuButtonHelper() {
        return new AppMenuButtonHelperImpl(this);
    }

    @Override
    public void invalidateAppMenu() {
        if (mAppMenu != null) mAppMenu.invalidate();
    }

    @Override
    public void addObserver(AppMenuObserver observer) {
        mObservers.add(observer);
    }

    @Override
    public void removeObserver(AppMenuObserver observer) {
        mObservers.remove(observer);
    }

    // StartStopWithNativeObserver implementation
    @Override
    public void onStartWithNative() {}

    @Override
    public void onStopWithNative() {
        hideAppMenu();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        hideAppMenu();
    }

    @VisibleForTesting
    void onOptionsItemSelected(int itemId, boolean highlighted) {
        if (mTestOptionsItemSelectedListener != null) {
            mTestOptionsItemSelectedListener.onResult(itemId);
            return;
        }
        String lastItemTitle = "";
        String lastVisibleItemTitle = "";
        int menuSize = mModelList.size();
        for (int i = 0; i < menuSize; i++) {
            if (mModelList.get(i).model.get(AppMenuItemProperties.MENU_ITEM_ID) == itemId) {
                Log.d("Kiwi", "Comparing Menu Size of Size: " + menuSize + " and ID: " + mModelList.get(i).model.get(AppMenuItemProperties.MENU_ITEM_ID) + " and itemId: " + itemId);
                CharSequence titleCondensed = mModelList.get(i).model.get(AppMenuItemProperties.TITLE_CONDENSED);
                lastItemTitle = titleCondensed.toString();
                CharSequence title = mModelList.get(i).model.get(AppMenuItemProperties.TITLE);
                lastVisibleItemTitle = title.toString();
                break;
            }
        }
        mAppMenuDelegate.setLastItemTitle(lastItemTitle);
        mAppMenuDelegate.setLastVisibleItemTitle(lastVisibleItemTitle);
        mAppMenuDelegate.onOptionsItemSelected(itemId, mDelegate.getBundleForMenuItem(itemId));
        if (highlighted) mDelegate.recordHighlightedMenuItemClicked(itemId);
    }

    /**
     * Called by AppMenu to report that the App Menu visibility has changed.
     * @param isVisible Whether the App Menu is showing.
     */
    void onMenuVisibilityChanged(boolean isVisible) {
        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onMenuVisibilityChanged(isVisible);
        }
    }

    /**
     * A notification that the header view has been inflated.
     * @param view The inflated view.
     */
    void onHeaderViewInflated(View view) {
        if (mDelegate != null) mDelegate.onHeaderViewInflated(this, view);
    }

    /**
     * A notification that the footer view has been inflated.
     * @param view The inflated view.
     */
    void onFooterViewInflated(View view) {
        if (mDelegate != null) mDelegate.onFooterViewInflated(this, view);
    }

    /**
     * Registers an {@link AppMenuBlocker} used to help determine whether the app menu can be shown.
     * @param blocker An {@link AppMenuBlocker} to check before attempting to show the app menu.
     */
    void registerAppMenuBlocker(AppMenuBlocker blocker) {
        if (!mBlockers.contains(blocker)) mBlockers.add(blocker);
    }

    /**
     * @param blocker The {@link AppMenuBlocker} to unregister.
     */
    void unregisterAppMenuBlocker(AppMenuBlocker blocker) {
        mBlockers.remove(blocker);
    }

    boolean shouldShowAppMenu() {
        for (int i = 0; i < mBlockers.size(); i++) {
            if (!mBlockers.get(i).canShowAppMenu()) return false;
        }
        return true;
    }

    @VisibleForTesting
    void overrideOnOptionItemSelectedListenerForTests(
            Callback<Integer> onOptionsItemSelectedListener) {
        mTestOptionsItemSelectedListener = onOptionsItemSelectedListener;
    }

    @VisibleForTesting
    AppMenuPropertiesDelegate getDelegateForTests() {
        return mDelegate;
    }

    private void registerViewBinders(@Nullable List<CustomViewBinder> customViewBinders,
            Map<CustomViewBinder, Integer> customViewTypeOffsetMap, ModelListAdapter adapter,
            boolean iconBeforeItem) {
        int standardItemResId = R.layout.menu_item;
        if (iconBeforeItem) {
            standardItemResId = R.layout.menu_item_start_with_icon;
        }

        adapter.registerType(AppMenuItemType.STANDARD, new LayoutViewBuilder(standardItemResId),
                AppMenuItemViewBinder::bindStandardItem);
        adapter.registerType(AppMenuItemType.TITLE_BUTTON,
                new LayoutViewBuilder(R.layout.title_button_menu_item),
                AppMenuItemViewBinder::bindTitleButtonItem);
        adapter.registerType(AppMenuItemType.THREE_BUTTON_ROW,
                new LayoutViewBuilder(R.layout.icon_row_menu_item),
                AppMenuItemViewBinder::bindIconRowItem);
        adapter.registerType(AppMenuItemType.FOUR_BUTTON_ROW,
                new LayoutViewBuilder(R.layout.icon_row_menu_item),
                AppMenuItemViewBinder::bindIconRowItem);
        adapter.registerType(AppMenuItemType.FIVE_BUTTON_ROW,
                new LayoutViewBuilder(R.layout.icon_row_menu_item),
                AppMenuItemViewBinder::bindIconRowItem);

        if (customViewBinders == null) return;
        for (int i = 0; i < customViewBinders.size(); i++) {
            CustomViewBinder binder = customViewBinders.get(i);
            if (customViewTypeOffsetMap.get(binder) == null) {
                continue;
            }

            for (int j = 0; j < binder.getViewTypeCount(); j++) {
                adapter.registerType(customViewTypeOffsetMap.get(binder) + j,
                        new LayoutViewBuilder(binder.getLayoutId(j)), binder);
            }
        }
    }

    void setupModelForHighlightAndClick(
            ModelList modelList, Integer highlightedId, AppMenuClickHandler appMenuClickHandler) {
        if (modelList == null) {
            return;
        }

        for (int i = 0; i < modelList.size(); i++) {
            PropertyModel model = modelList.get(i).model;
            // Not like other keys which is set by AppMenuPropertiesDelegateImpl, CLICK_HANDLER and
            // HIGHLIGHTED should not be set yet.
            assert model.get(AppMenuItemProperties.CLICK_HANDLER) == null;
            assert !model.get(AppMenuItemProperties.HIGHLIGHTED);
            model.set(AppMenuItemProperties.CLICK_HANDLER, appMenuClickHandler);
            model.set(AppMenuItemProperties.POSITION, i);

            if (highlightedId != null) {
                model.set(AppMenuItemProperties.HIGHLIGHTED,
                        model.get(AppMenuItemProperties.MENU_ITEM_ID) == highlightedId);
                if (model.get(AppMenuItemProperties.SUBMENU) != null) {
                    ModelList subList = model.get(AppMenuItemProperties.SUBMENU);
                    for (int j = 0; j < subList.size(); j++) {
                        PropertyModel subModel = subList.get(j).model;
                        subModel.set(AppMenuItemProperties.CLICK_HANDLER, appMenuClickHandler);
                        subModel.set(AppMenuItemProperties.HIGHLIGHTED,
                                subModel.get(AppMenuItemProperties.MENU_ITEM_ID) == highlightedId);
                    }
                }
            }
        }
    }

    private Map<CustomViewBinder, Integer> populateCustomViewBinderOffsetMap(
            @Nullable List<CustomViewBinder> customViewBinders, int startingOffset) {
        Map<CustomViewBinder, Integer> customViewTypeOffsetMap = new HashMap<>();
        if (customViewBinders == null) return customViewTypeOffsetMap;

        int currentOffset = startingOffset;
        for (int i = 0; i < customViewBinders.size(); i++) {
            CustomViewBinder binder = customViewBinders.get(i);
            customViewTypeOffsetMap.put(binder, currentOffset);
            currentOffset += binder.getViewTypeCount();
        }
        return customViewTypeOffsetMap;
    }

    private int getCustomItemViewType(int id, List<CustomViewBinder> customViewBinders,
            Map<CustomViewBinder, Integer> customViewTypeOffsetMap) {
        if (customViewBinders == null || customViewTypeOffsetMap == null) {
            return CustomViewBinder.NOT_HANDLED;
        }

        for (int i = 0; i < customViewBinders.size(); i++) {
            CustomViewBinder binder = customViewBinders.get(i);
            int binderViewType = binder.getItemViewType(id);
            if (binderViewType != CustomViewBinder.NOT_HANDLED) {
                return binderViewType + customViewTypeOffsetMap.get(binder);
            }
        }
        return CustomViewBinder.NOT_HANDLED;
    }

    /** @param reporter A means of reporting an exception without crashing. */
    static void setExceptionReporter(Callback<Throwable> reporter) {
        AppMenu.setExceptionReporter(reporter);
    }
}
