#!/usr/bin/env python3
# Copyright 2025 Kiwi Browser contributors.
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Apply the Lemur-style (rounded bottom sheet) app menu to a Kiwi/Chromium 105 tree.

This is a Chromium 105 / Kiwi port of android-helium-browser's
``patch_lemur_app_menu.py``. Unlike the Chromium 156 original it does not
hard-code menu item ids: the bottom sheet is generated from the ``ModelList``
that ``AppMenuPropertiesDelegateImpl.getMenuItems()`` already produces, which is
what makes it portable across menu groups (page / overview / tablet empty) and
across Kiwi's heavily customised menu.

Usage:
    python3 patch_lemur_app_menu.py <chromium-src-dir>

The script is idempotent: every edit is guarded by a marker, so it can be
re-run after a Kiwi rebase to repair conflicts.
"""

from pathlib import Path
import sys


MENU_BUTTON_LAYOUT_REL = (
    "chrome/browser/ui/android/toolbar/java/res/layout/menu_button.xml"
)
MENU_BUTTON_DRAWABLE_REL = (
    "chrome/browser/ui/android/toolbar/java/res/drawable/ic_lemur_menu_lines.xml"
)
HANDLER_IMPL_REL = (
    "chrome/browser/ui/android/appmenu/internal/java/src/org/chromium/chrome/"
    "browser/ui/appmenu/AppMenuHandlerImpl.java"
)
APP_MENU_TEST_REL = (
    "chrome/browser/ui/android/appmenu/internal/java/src/org/chromium/chrome/"
    "browser/ui/appmenu/AppMenuTest.java"
)

# Resource-free labels. Move these to strings.xml before shipping a release.
LEMUR_SECTION_EXTENSIONS = "扩展"
LEMUR_SECTION_MORE = "更多"

LEMUR_MENU_ICON_DRAWABLE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Lemur style three-horizontal-lines toolbar menu icon. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4.2,6.5h15.6v2H4.2z" />
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4.2,11h15.6v2H4.2z" />
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4.2,15.5h15.6v2H4.2z" />
</vector>
"""

LEMUR_IMPORTS = """import android.app.Activity;
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
"""

LEMUR_FIELDS = """    // Lemur style bottom sheet app menu state.
    private static @Nullable Boolean sLemurMenuEnabledForTesting;
    private @Nullable Dialog mLemurDialog;
    private boolean mLemurSuppressDismissCallback;
"""

# The menu models are read from the field, so the hook works both on Chromium
# 105 (where showAppMenu keeps them in a local named modelList) and on 132+
# (where they are assigned straight to mModelList).
LEMUR_HOOK_BODY = """
        // Lemur style: present the menu as a rounded bottom sheet. The sheet is
        // generated from the PropertyModels above, so it stays in sync with
        // every menu Kiwi builds (page / overview / tablet empty).
        if (!startDragging && showLemurMenu(mModelList)) {
            clearMenuHighlight();
            RecordUserAction.record("MobileMenuShow");
            onMenuVisibilityChanged(true);
            return true;
        }
"""

# Insertion points, tried in order. Chromium 132+ registers a model list observer
# inside showAppMenu(), so the sheet must be opened before that line to avoid
# having to unregister it on dismiss.
LEMUR_HOOK_BEFORE_ANCHORS = [
    "        mModelList.addObserver(mListObserver);\n",
]
LEMUR_HOOK_AFTER_ANCHORS = [
    "        mModelList = modelList;\n",
]

LEMUR_METHODS = """    // ---------------------------------------------------------------------
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
            checkView.setText("\\u2713");
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

"""


def patch_menu_button_icon(src_dir: Path) -> None:
    """Replaces the three-dot toolbar icon with Lemur's three-horizontal-lines icon."""
    layout_path = src_dir / MENU_BUTTON_LAYOUT_REL
    if not layout_path.exists():
        raise SystemExit("File not found: %s" % layout_path)

    drawable_path = src_dir / MENU_BUTTON_DRAWABLE_REL
    drawable_path.parent.mkdir(parents=True, exist_ok=True)
    drawable_path.write_text(LEMUR_MENU_ICON_DRAWABLE, encoding="utf-8")

    text = layout_path.read_text(encoding="utf-8")
    if "ic_lemur_menu_lines" not in text:
        anchor = 'android:src="@drawable/ic_more_vert_24dp"'
        if anchor not in text:
            raise SystemExit("menu_button src anchor not found in %s" % layout_path)
        text = text.replace(anchor, 'android:src="@drawable/ic_lemur_menu_lines"', 1)
        layout_path.write_text(text, encoding="utf-8")


def patch_app_menu_handler_impl(src_dir: Path) -> None:
    """Injects the Lemur bottom-sheet implementation into AppMenuHandlerImpl."""
    path = src_dir / HANDLER_IMPL_REL
    if not path.exists():
        raise SystemExit("File not found: %s" % path)

    text = path.read_text(encoding="utf-8")

    # 1. Imports.
    if "org.chromium.chrome.browser.preferences.SharedPreferencesManager" not in text:
        import_anchor = "import android.view.View;\n"
        if import_anchor not in text:
            raise SystemExit("import anchor not found in %s" % path)
        text = text.replace(import_anchor, import_anchor + LEMUR_IMPORTS, 1)

    # 2. Fields.
    if "mLemurDialog" not in text:
        field_anchor = "    private ModelList mModelList;\n"
        if field_anchor not in text:
            raise SystemExit("mModelList anchor not found in %s" % path)
        text = text.replace(field_anchor, field_anchor + LEMUR_FIELDS, 1)

    # 3. Hook into showAppMenu().
    if "showLemurMenu(" not in text:
        hooked = False
        for anchor in LEMUR_HOOK_BEFORE_ANCHORS:
            if anchor in text:
                text = text.replace(anchor, LEMUR_HOOK_BODY + anchor, 1)
                hooked = True
                break
        if not hooked:
            for anchor in LEMUR_HOOK_AFTER_ANCHORS:
                if anchor in text:
                    text = text.replace(anchor, anchor + LEMUR_HOOK_BODY, 1)
                    hooked = True
                    break
        if not hooked:
            raise SystemExit(
                "showAppMenu hook anchor not found in %s (known anchors: %s)"
                % (path, LEMUR_HOOK_BEFORE_ANCHORS + LEMUR_HOOK_AFTER_ANCHORS)
            )

    # 4. Register the sheet in the visibility plumbing.
    is_showing_old = "        return mAppMenu != null && mAppMenu.isShowing();\n"
    is_showing_new = (
        "        return (mAppMenu != null && mAppMenu.isShowing()) || isLemurMenuShowing();\n"
    )
    if is_showing_new not in text:
        if is_showing_old not in text:
            raise SystemExit("isAppMenuShowing anchor not found in %s" % path)
        text = text.replace(is_showing_old, is_showing_new, 1)

    hide_anchor = "    public void hideAppMenu() {\n"
    if "hideLemurMenu();" not in text:
        if hide_anchor not in text:
            raise SystemExit("hideAppMenu anchor not found in %s" % path)
        text = text.replace(hide_anchor, hide_anchor + "        hideLemurMenu();\n", 1)

    # 5. Implementation block.
    block_anchor = "    void appMenuDismissed() {\n"
    if "private boolean showLemurMenu(" not in text:
        if block_anchor not in text:
            raise SystemExit("appMenuDismissed anchor not found in %s" % path)
        text = text.replace(block_anchor, LEMUR_METHODS + block_anchor, 1)

    path.write_text(text, encoding="utf-8")


def patch_app_menu_test(src_dir: Path) -> None:
    """Keeps the native popup menu in app menu integration tests."""
    path = src_dir / APP_MENU_TEST_REL
    if not path.exists():
        # Test file may be absent in a stripped-down checkout; not fatal.
        return

    text = path.read_text(encoding="utf-8")
    if "setLemurMenuEnabledForTesting" in text:
        return

    anchor = (
        "    private void setUpTestOnUiThread() {\n"
        "        mLifecycleDispatcher = new TestActivityLifecycleDispatcher();\n"
    )
    if anchor not in text:
        # Fall back to a no-op: the sheet is skipped when no Activity is resolvable.
        return
    replacement = (
        "    private void setUpTestOnUiThread() {\n"
        "        AppMenuHandlerImpl.setLemurMenuEnabledForTesting(false);\n"
        "        mLifecycleDispatcher = new TestActivityLifecycleDispatcher();\n"
    )
    path.write_text(text.replace(anchor, replacement, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_lemur_app_menu.py <chromium-src-dir>")

    src_dir = Path(sys.argv[1]).resolve()
    patch_menu_button_icon(src_dir)
    patch_app_menu_handler_impl(src_dir)
    patch_app_menu_test(src_dir)
    print(
        "Applied Lemur-style app menu (three-line toolbar icon + rounded "
        "bottom sheet) to %s" % src_dir
    )


if __name__ == "__main__":
    main()
