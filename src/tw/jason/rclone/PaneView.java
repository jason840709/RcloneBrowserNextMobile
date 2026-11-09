package tw.jason.rclone;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** One browsing window: header (location, path, search, menu), a progress line and the file list. */
final class PaneView extends LinearLayout {

    interface Host {
        void activate(PaneView p);
        void openEntry(PaneView p, FileOps.Entry e);
        void selectionChanged(PaneView p);
        void navigate(PaneView p, String path);
        void showLocations(PaneView p, View anchor);
        void showMenu(PaneView p, View anchor);
        void retry(PaneView p);
        void cancelLoad(PaneView p);
        void requestAccess();
        boolean needsAccess(String path);
        String rootLabel(String root);
        String localRoot(String path);
        String displayPath(String path);
        int locationIcon(String path);
        Comparator<FileOps.Entry> comparator();
        boolean showHidden();
    }

    final int index;
    private final Host host;

    // State
    String path = FileOps.PHONE;
    List<FileOps.Entry> entries = new ArrayList<>();
    final LinkedHashSet<String> selected = new LinkedHashSet<>();
    boolean loading, loadedOnce;
    int generation;
    String error = "", failed = "";
    String filter = "";
    boolean searching;
    private final HashMap<String, int[]> scrollMemory = new HashMap<>();

    // Views
    private final FrameLayout header;
    private final LinearLayout normalBar, searchBar, selectBar;
    private final FrameLayout locTile;
    private final ImageView locIcon, locChevron;
    private final Breadcrumb crumbs;
    private final EditText searchField;
    private final ImageView clearSearch;
    private final TextView selectCount;
    private final View headerLine;
    private final ProgressLine progress;
    private final ListView list;
    private final LinearLayout stateView;
    private final FrameLayout loadingView;
    private final TextView loadingTitle, loadingHint, loadingStop;
    private final TransferUi.Ring loadingRing;
    private final Runnable showLoading = this::revealLoading, showLoadingHelp = this::revealLoadingHelp;
    private final Adapter adapter = new Adapter();
    private final ArrayList<FileOps.Entry> shown = new ArrayList<>();
    private int headerMode = -1;
    private boolean active, highlight;
    private int headerColor;
    private ValueAnimator headerTint;
    private int topInset;
    private boolean lineShown;

    static final int NORMAL = 0, SEARCH = 1, SELECT = 2;

    PaneView(Context c, int index, Host host) {
        super(c);
        this.index = index;
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.surface);

        header = new FrameLayout(c);
        headerColor = Ui.surface;
        header.setBackgroundColor(headerColor);
        header.setOnClickListener(v -> host.activate(this));

        // Normal header: location tile, path, search, more
        normalBar = Ui.row(c);
        normalBar.setPadding(Ui.dp(8), 0, Ui.dp(4), 0);
        locTile = new FrameLayout(c);
        LinearLayout locInner = Ui.row(c);
        locInner.setGravity(Gravity.CENTER);
        locIcon = Ui.icon(c, R.drawable.ic_phone, Ui.primary);
        locInner.addView(locIcon, new LayoutParams(Ui.dp(20), Ui.dp(20)));
        locChevron = Ui.icon(c, R.drawable.ic_expand_more, Ui.primary);
        locInner.addView(locChevron, new LayoutParams(Ui.dp(16), Ui.dp(16)));
        locTile.addView(locInner, Ui.frame(-1, -1, Gravity.CENTER));
        locTile.setPadding(Ui.dp(4), 0, 0, 0);
        locTile.setBackground(Ui.press(Ui.primaryContainer, 12));
        locTile.setContentDescription("切換位置");
        locTile.setOnClickListener(v -> { host.activate(this); host.showLocations(this, v); });
        LayoutParams lt = new LayoutParams(Ui.dp(52), Ui.dp(40));
        lt.leftMargin = Ui.dp(4);
        lt.rightMargin = Ui.dp(4);
        normalBar.addView(locTile, lt);
        crumbs = new Breadcrumb(c);
        normalBar.addView(crumbs, new LayoutParams(0, -1, 1));
        normalBar.addView(Ui.iconButton(c, R.drawable.ic_search, "搜尋這個資料夾", Ui.muted, v -> { host.activate(this); startSearch(); }));
        normalBar.addView(Ui.iconButton(c, R.drawable.ic_more, "更多選項", Ui.muted, v -> { host.activate(this); host.showMenu(this, v); }));
        header.addView(normalBar, new FrameLayout.LayoutParams(-1, Ui.dp(56), Gravity.BOTTOM));

        // Search header: a rounded field
        searchBar = Ui.row(c);
        searchBar.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        LinearLayout pill = Ui.row(c);
        pill.setBackground(Ui.round(Ui.surfaceHigh, 22));
        pill.addView(Ui.iconButton(c, R.drawable.ic_back, "結束搜尋", Ui.ink, v -> endSearch()));
        searchField = new EditText(c);
        searchField.setBackground(null);
        searchField.setHint("搜尋這個資料夾");
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchField.setTextColor(Ui.ink);
        searchField.setHintTextColor(Ui.muted);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setOnEditorActionListener((v, id, ev) -> { hideKeyboard(); return true; });
        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) {
                if (!searching) return;
                filter = s.toString();
                clearSearch.setVisibility(filter.isEmpty() ? INVISIBLE : VISIBLE);
                refresh();
            }
        });
        pill.addView(searchField, new LayoutParams(0, -1, 1));
        clearSearch = Ui.iconButton(c, R.drawable.ic_close, "清除搜尋文字", Ui.muted, v -> searchField.setText(""));
        clearSearch.setVisibility(INVISIBLE);
        pill.addView(clearSearch);
        searchBar.addView(pill, new LayoutParams(-1, -1));
        header.addView(searchBar, new FrameLayout.LayoutParams(-1, Ui.dp(56), Gravity.BOTTOM));

        // Selection header
        selectBar = Ui.row(c);
        selectBar.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        selectBar.addView(Ui.iconButton(c, R.drawable.ic_close, "取消選取", Ui.onPrimaryContainer, v -> clearSelection()));
        selectCount = Ui.title(c, "", 18, Ui.onPrimaryContainer);
        selectCount.setPadding(Ui.dp(8), 0, 0, 0);
        selectBar.addView(selectCount, new LayoutParams(0, -2, 1));
        TextView all = Ui.button(c, "全選", Ui.ButtonStyle.TEXT, v -> selectAllShown());
        all.setTextColor(Ui.onPrimaryContainer);
        selectBar.addView(all);
        header.addView(selectBar, new FrameLayout.LayoutParams(-1, Ui.dp(56), Gravity.BOTTOM));

        progress = new ProgressLine(c);
        header.addView(progress, new FrameLayout.LayoutParams(-1, Ui.dp(3), Gravity.BOTTOM));
        addView(header, new LayoutParams(-1, Ui.dp(56)));

        headerLine = new View(c);
        headerLine.setBackgroundColor(Ui.line);
        headerLine.setAlpha(0f);
        addView(headerLine, new LayoutParams(-1, Math.max(1, Ui.dp(1))));

        FrameLayout body = new FrameLayout(c);
        list = new ListView(c);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(4), 0, Ui.dp(12));
        list.setScrollBarStyle(SCROLLBARS_OUTSIDE_OVERLAY);
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) {}
            public void onScroll(AbsListView v, int first, int visible, int total) {
                boolean scrolled = v.getChildCount() > 0 && (first > 0 || v.getChildAt(0).getTop() < v.getPaddingTop());
                if (scrolled == lineShown) return;
                lineShown = scrolled;
                headerLine.animate().alpha(scrolled ? 1f : 0f).setDuration(150).start();
            }
        });
        list.setOnItemClickListener((a, v, pos, id) -> {
            if (pos >= shown.size()) return;
            FileOps.Entry e = shown.get(pos);
            if (!selected.isEmpty()) toggle(e);
            else host.openEntry(this, e);
        });
        list.setOnItemLongClickListener((a, v, pos, id) -> {
            if (pos >= shown.size()) return true;
            FileOps.Entry e = shown.get(pos);
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (searching) { hideKeyboard(); searchField.clearFocus(); }
            if (!selected.contains(e.path)) { selected.add(e.path); afterSelection(); }
            return true;
        });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        stateView = Ui.column(c);
        stateView.setGravity(Gravity.CENTER_HORIZONTAL);
        stateView.setPadding(Ui.dp(32), Ui.dp(40), Ui.dp(32), Ui.dp(24));
        stateView.setVisibility(GONE);
        body.addView(stateView, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Loading cover: appears only when a folder takes a moment, so quick folders never flash it.
        // It also blocks taps on the old list, which is dimmed behind it.
        loadingView = new FrameLayout(c);
        loadingView.setClickable(true);
        loadingView.setVisibility(GONE);
        LinearLayout card = Ui.column(c);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(Ui.surfaceContainer, 24));
        card.setElevation(Ui.dp(2));
        card.setPadding(Ui.dp(24), Ui.dp(22), Ui.dp(24), Ui.dp(18));
        loadingRing = new TransferUi.Ring(c, Ui.primary);
        card.addView(loadingRing, new LayoutParams(Ui.dp(36), Ui.dp(36)));
        loadingTitle = Ui.oneLine(Ui.title(c, "", 15.5f, Ui.ink), TextUtils.TruncateAt.MIDDLE);
        loadingTitle.setGravity(Gravity.CENTER);
        LayoutParams tl = new LayoutParams(-2, -2);
        tl.topMargin = Ui.dp(14);
        card.addView(loadingTitle, tl);
        loadingHint = Ui.text(c, "項目很多或網路較慢時會比較久。停止讀取會留在原本的資料夾。", 13.5f, Ui.muted);
        loadingHint.setGravity(Gravity.CENTER);
        loadingHint.setPadding(0, Ui.dp(6), 0, 0);
        loadingHint.setVisibility(GONE);
        card.addView(loadingHint, new LayoutParams(-2, -2));
        loadingStop = Ui.button(c, "停止讀取", Ui.ButtonStyle.TEXT, v -> host.cancelLoad(this));
        loadingStop.setContentDescription("停止讀取，留在原本的資料夾");
        loadingStop.setVisibility(GONE);
        LayoutParams sl = new LayoutParams(-2, -2);
        sl.topMargin = Ui.dp(8);
        card.addView(loadingStop, sl);
        card.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        cl.topMargin = Ui.dp(72);
        cl.leftMargin = cl.rightMargin = Ui.dp(24);
        loadingView.addView(card, cl);
        body.addView(loadingView, new FrameLayout.LayoutParams(-1, -1));
        addView(body, new LayoutParams(-1, 0, 1));

        applyHeaderMode(false);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) host.activate(this);
        return false;
    }

    // ---- Layout hooks -------------------------------------------------------------------------

    void setTopInset(int inset) {
        if (inset == topInset && header.getLayoutParams().height == Ui.dp(56) + inset) return;
        topInset = inset;
        header.getLayoutParams().height = Ui.dp(56) + inset;
        header.requestLayout();
    }

    void setBottomPadding(int px) {
        list.setPadding(0, Ui.dp(4), 0, px + Ui.dp(12));
        stateView.setPadding(Ui.dp(32), Ui.dp(40), Ui.dp(32), px + Ui.dp(24));
    }

    /** In split view the active window gets a tinted header; elsewhere the header matches the list. */
    void setActiveLook(boolean active, boolean highlight) {
        boolean changed = this.active != active || this.highlight != highlight;
        this.active = active;
        this.highlight = highlight;
        tintHeader(changed);
    }

    private void tintHeader(boolean animate) {
        int target = headerMode == SELECT ? Ui.primaryContainer : (highlight && active ? Ui.activeHeader : Ui.surface);
        boolean dim = highlight && !active && headerMode != SELECT;
        locTile.setBackground(Ui.press(dim ? Ui.surfaceHigh : Ui.primaryContainer, 12));
        locIcon.setImageTintList(android.content.res.ColorStateList.valueOf(dim ? Ui.muted : Ui.primary));
        locChevron.setImageTintList(android.content.res.ColorStateList.valueOf(dim ? Ui.muted : Ui.primary));
        if (headerTint != null) headerTint.cancel();
        if (!animate || !Ui.animationsOn() || target == headerColor) {
            headerColor = target;
            header.setBackgroundColor(target);
            return;
        }
        headerTint = ValueAnimator.ofObject(new ArgbEvaluator(), headerColor, target);
        headerTint.setDuration(180);
        headerTint.setInterpolator(Ui.STANDARD);
        headerTint.addUpdateListener(a -> { headerColor = (Integer) a.getAnimatedValue(); header.setBackgroundColor(headerColor); });
        headerTint.start();
    }

    View anchorForMenu() { return normalBar.getChildAt(normalBar.getChildCount() - 1); }

    // ---- Data ---------------------------------------------------------------------------------

    /** Shows a freshly loaded folder. direction: +1 deeper, -1 up, 0 same folder. */
    void showEntries(String newPath, List<FileOps.Entry> items, int direction) {
        boolean moved = !newPath.equals(path);
        if (moved) rememberScroll();
        path = newPath;
        entries = items;
        error = "";
        failed = "";
        loadedOnce = true;
        if (moved) {
            selected.clear();
            if (searching) endSearch();
        } else {
            // Keep selection for items that still exist after a refresh.
            LinkedHashSet<String> still = new LinkedHashSet<>();
            for (FileOps.Entry e : items) if (selected.contains(e.path)) still.add(e.path);
            selected.retainAll(still);
        }
        refresh();
        if (moved) {
            int[] mem = scrollMemory.get(newPath);
            if (shown.isEmpty()) { /* nothing to position */ }
            else if (direction < 0 && mem != null) list.setSelectionFromTop(Math.min(mem[0], shown.size() - 1), mem[1]);
            else list.setSelection(0);
            if (direction != 0 && Ui.animationsOn()) {
                list.animate().cancel();
                list.setTranslationX(direction * Ui.dp(28));
                list.setAlpha(0f);
                list.animate().translationX(0).alpha(1f).setDuration(240).setInterpolator(Ui.ENTER).start();
            }
        }
    }

    private void rememberScroll() {
        if (list.getChildCount() == 0) return;
        scrollMemory.put(path, new int[]{list.getFirstVisiblePosition(), list.getChildAt(0).getTop() - list.getPaddingTop()});
        if (scrollMemory.size() > 64) scrollMemory.clear();
    }

    void showError(String target, String message) {
        failed = target;
        error = message;
        refresh();
    }

    void setLoading(boolean on) { setLoading(on, null); }

    /** target names the folder being read. */
    void setLoading(boolean on, String target) {
        loadingState(on, target == null ? "正在讀取…" : "正在讀取「" + label(target) + "」…", true);
    }

    /** Work that cannot be stopped from here, such as deleting; the cover shows title without a stop button. */
    void setWorking(String title) { loadingState(true, title, false); }

    private boolean stoppable = true;

    private void loadingState(boolean on, String title, boolean canStop) {
        loading = on;
        stoppable = canStop;
        progress.setRunning(on);
        removeCallbacks(showLoading);
        removeCallbacks(showLoadingHelp);
        if (on) {
            loadingTitle.setText(title);
            if (loadingView.getVisibility() == VISIBLE) revealLoading();
            else {
                loadingHint.setVisibility(GONE);
                loadingStop.setVisibility(GONE);
                postDelayed(showLoading, 300);
            }
            postDelayed(showLoadingHelp, 2500);
        } else if (loadingView.getVisibility() == VISIBLE) {
            loadingRing.stop();
            loadingView.animate().cancel();
            loadingView.animate().alpha(0f).setDuration(120).withEndAction(() -> { if (!loading) loadingView.setVisibility(GONE); }).start();
            list.animate().alpha(1f).setDuration(150).start();
            stateView.animate().alpha(1f).setDuration(150).start();
        }
    }

    private void revealLoading() {
        if (!loading) return;
        loadingRing.setFraction(-1);
        loadingView.animate().cancel();
        if (loadingView.getVisibility() != VISIBLE) { loadingView.setAlpha(0f); loadingView.setVisibility(VISIBLE); }
        loadingView.animate().alpha(1f).setDuration(150).withEndAction(null).start();
        list.animate().cancel();
        list.animate().alpha(.35f).setDuration(150).start();
        // An empty-folder or error message from before would read as the answer; hide it until the result.
        stateView.animate().cancel();
        stateView.animate().alpha(0f).setDuration(120).start();
    }

    private void revealLoadingHelp() {
        if (!loading || !stoppable) return;
        loadingHint.setVisibility(VISIBLE);
        loadingStop.setVisibility(VISIBLE);
    }

    /** Re-filters, re-sorts and redraws everything that depends on state. Cheap; call freely. */
    void refresh() {
        shown.clear();
        String needle = filter.toLowerCase();
        boolean hidden = host.showHidden();
        int hiddenCount = 0;
        for (FileOps.Entry e : entries) {
            if (!hidden && e.name.startsWith(".")) { hiddenCount++; continue; }
            if (!needle.isEmpty() && !e.name.toLowerCase().contains(needle)) continue;
            shown.add(e);
        }
        Collections.sort(shown, host.comparator());
        adapter.notifyDataSetChanged();
        list.setFastScrollEnabled(shown.size() > 80);
        crumbs.set(path);
        locIcon.setImageResource(host.locationIcon(path));
        locTile.setContentDescription("切換位置，目前在" + host.rootLabel(host.localRoot(path)));
        selectCount.setText("已選 " + selected.size() + " 項");
        applyHeaderMode(true);
        updateState(hiddenCount);
    }

    private void updateState(int hiddenCount) {
        stateView.removeAllViews();
        boolean showList = true;
        if (FileOps.isLocal(path) && host.needsAccess(path)) {
            state(R.drawable.ic_lock, "需要檔案存取權限", "允許後才能瀏覽手機裡的檔案。");
            stateButton("允許檔案存取", Ui.ButtonStyle.FILLED, host::requestAccess);
            showList = false;
        } else if (!error.isEmpty()) {
            int icon = error.contains("權限") ? R.drawable.ic_lock
                    : (error.contains("逾時") || error.contains("連不到") || error.contains("網路")) && !FileOps.isLocal(failed) ? R.drawable.ic_cloud_off
                    : R.drawable.ic_error;
            state(icon, "無法開啟 " + host.displayPath(failed), error);
            stateButton("重試", Ui.ButtonStyle.TONAL, () -> host.retry(this));
            showList = false;
        } else if (shown.isEmpty() && loadedOnce && !loading) {
            if (!filter.isEmpty()) state(R.drawable.ic_search, "找不到「" + filter + "」", null);
            else if (hiddenCount > 0) state(R.drawable.ic_visibility, "只有隱藏的項目", "從右上角選單開啟「顯示隱藏檔」就能看到。");
            else state(R.drawable.ic_folder_open, "這個資料夾是空的", null);
        }
        stateView.setVisibility(stateView.getChildCount() > 0 ? VISIBLE : GONE);
        list.setVisibility(showList ? VISIBLE : INVISIBLE);
    }

    private void state(int icon, String title, String message) {
        Context c = getContext();
        FrameLayout disc = new FrameLayout(c);
        disc.setBackground(Ui.round(Ui.surfaceContainer, 28));
        disc.addView(Ui.icon(c, icon, Ui.muted), Ui.frame(Ui.dp(28), Ui.dp(28), Gravity.CENTER));
        LayoutParams dp = new LayoutParams(Ui.dp(64), Ui.dp(64));
        dp.bottomMargin = Ui.dp(16);
        stateView.addView(disc, dp);
        TextView t = Ui.title(c, title, 17, Ui.ink);
        t.setGravity(Gravity.CENTER);
        stateView.addView(t, new LayoutParams(-2, -2));
        if (message != null && !message.isEmpty()) {
            TextView m = Ui.text(c, message, 14.5f, Ui.muted);
            m.setGravity(Gravity.CENTER);
            m.setLineSpacing(0, 1.25f);
            m.setPadding(0, Ui.dp(6), 0, 0);
            stateView.addView(m, new LayoutParams(-2, -2));
        }
    }

    private void stateButton(String label, Ui.ButtonStyle style, Runnable action) {
        TextView b = Ui.button(getContext(), label, style, v -> action.run());
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(20);
        stateView.addView(b, lp);
    }

    String label(String p) {
        if (p.endsWith(":") || host.localRoot(p).equals(p)) return host.rootLabel(p);
        String s = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // ---- Selection ----------------------------------------------------------------------------

    private void toggle(FileOps.Entry e) {
        if (!selected.remove(e.path)) selected.add(e.path);
        afterSelection();
    }

    private void afterSelection() {
        refresh();
        host.selectionChanged(this);
    }

    void selectAllShown() {
        for (FileOps.Entry e : shown) selected.add(e.path);
        afterSelection();
    }

    void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        afterSelection();
    }

    ArrayList<FileOps.Entry> selectedEntries() {
        ArrayList<FileOps.Entry> out = new ArrayList<>();
        for (FileOps.Entry e : entries) if (selected.contains(e.path)) out.add(e);
        return out;
    }

    // ---- Search -------------------------------------------------------------------------------

    void startSearch() {
        searching = true;
        selected.clear();
        searchField.setText(filter);
        searchField.setSelection(filter.length());
        applyHeaderMode(true);
        Overlay.showKeyboard(searchField);
        host.selectionChanged(this);
    }

    void endSearch() {
        searching = false;
        filter = "";
        hideKeyboard();
        searchField.setText("");
        refresh();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    // ---- Header modes -------------------------------------------------------------------------

    private void applyHeaderMode(boolean animate) {
        int mode = !selected.isEmpty() ? SELECT : searching ? SEARCH : NORMAL;
        if (mode == headerMode) return;
        int old = headerMode;
        headerMode = mode;
        View[] bars = {normalBar, searchBar, selectBar};
        for (int i = 0; i < bars.length; i++) {
            final View b = bars[i];
            b.animate().cancel();
            if (i == mode) {
                b.setVisibility(VISIBLE);
                if (animate && old >= 0 && Ui.animationsOn()) {
                    b.setAlpha(0f);
                    b.setTranslationY(Ui.dp(6));
                    b.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(Ui.ENTER).withEndAction(null).start();
                } else { b.setAlpha(1f); b.setTranslationY(0); }
            } else if (animate && i == old && Ui.animationsOn()) {
                b.animate().alpha(0f).setDuration(100).setInterpolator(Ui.EXIT).withEndAction(() -> b.setVisibility(GONE)).start();
            } else {
                b.setVisibility(GONE);
            }
        }
        tintHeader(animate);
    }

    // ---- Adapter ------------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int p) { return shown.get(p); }
        public long getItemId(int p) { return p >= 0 && p < shown.size() ? shown.get(p).path.hashCode() : p; }
        @Override public boolean hasStableIds() { return true; }
        public View getView(int pos, View old, ViewGroup parent) {
            View wrap = old != null ? old : FileRow.wrap(new FileRow(getContext()));
            FileRow row = (FileRow) wrap.getTag();
            FileOps.Entry e = shown.get(pos);
            row.bind(e, selected.contains(e.path), !selected.isEmpty());
            return wrap;
        }
    }

    // ---- Breadcrumb ---------------------------------------------------------------------------

    /** Tappable path. Earlier folders are muted; the current one is larger. Scrolls to the end. */
    private final class Breadcrumb extends HorizontalScrollView {
        private final LinearLayout strip;
        private String shownPath;

        Breadcrumb(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength(Ui.dp(24));
            setOverScrollMode(OVER_SCROLL_NEVER);
            strip = Ui.row(c);
            addView(strip, new FrameLayout.LayoutParams(-2, -1));
        }

        @Override protected float getRightFadingEdgeStrength() { return 0f; }

        /** Keep the current folder visible after rotation, resizing the split, or a new path. */
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (getChildCount() > 0) scrollTo(Math.max(0, getChildAt(0).getWidth() - getWidth()), 0);
        }

        void set(String p) {
            if (p.equals(shownPath)) return;
            shownPath = p;
            strip.removeAllViews();
            ArrayList<String[]> parts = segments(p);
            for (int i = 0; i < parts.size(); i++) {
                final String[] part = parts.get(i);
                boolean last = i == parts.size() - 1;
                if (i > 0) {
                    ImageView sep = Ui.icon(getContext(), R.drawable.ic_chevron_right, Ui.alpha(Ui.muted, .7f));
                    strip.addView(sep, new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
                }
                TextView t = last ? Ui.title(getContext(), part[0], 17, Ui.ink) : Ui.text(getContext(), part[0], 15, Ui.muted);
                t.setSingleLine(true);
                t.setMaxWidth(Ui.dp(last ? 240 : 140));
                t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
                t.setBackground(Ui.press(0, 10));
                t.setContentDescription(last ? "目前位置：" + part[0] : "前往 " + part[0]);
                t.setOnClickListener(v -> { host.activate(PaneView.this); if (!part[1].equals(path)) host.navigate(PaneView.this, part[1]); });
                strip.addView(t, new LinearLayout.LayoutParams(-2, Ui.dp(40)));
            }
            requestLayout();
        }

        private ArrayList<String[]> segments(String p) {
            ArrayList<String[]> out = new ArrayList<>();
            String root, rest;
            if (FileOps.isLocal(p)) {
                root = host.localRoot(p);
                rest = root.equals("/") ? p : p.substring(root.length());
            } else {
                int colon = p.indexOf(':');
                root = p.substring(0, colon + 1);
                rest = p.substring(colon + 1);
            }
            out.add(new String[]{host.rootLabel(root), root});
            String acc = root;
            for (String s : rest.split("/")) {
                if (s.isEmpty()) continue;
                acc = FileOps.join(acc, s);
                out.add(new String[]{s, acc});
            }
            return out;
        }
    }

    // ---- Progress line ------------------------------------------------------------------------

    /** Thin indeterminate bar along the bottom of the header. Appears only if loading lasts. */
    private static final class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float phase;
        private boolean running;

        ProgressLine(Context c) {
            super(c);
            paint.setColor(Ui.primary);
            setAlpha(0f);
        }

        void setRunning(boolean on) {
            if (on == running) return;
            running = on;
            animate().cancel();
            if (on) {
                if (anim == null) {
                    anim = ValueAnimator.ofFloat(0f, 1f);
                    anim.setDuration(1300);
                    anim.setRepeatCount(ValueAnimator.INFINITE);
                    anim.setInterpolator(new android.view.animation.LinearInterpolator());
                    anim.addUpdateListener(a -> { phase = (Float) a.getAnimatedValue(); invalidate(); });
                }
                anim.start();
                // Fast local folders finish before this delay, so they never flash a bar.
                animate().alpha(1f).setStartDelay(160).setDuration(150).start();
            } else {
                animate().alpha(0f).setStartDelay(0).setDuration(180).withEndAction(() -> { if (!running && anim != null) anim.cancel(); }).start();
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float head = Ui.ENTER.getInterpolation(Math.min(1f, phase * 1.25f));
            float tail = Ui.STANDARD.getInterpolation(Math.max(0f, phase * 1.25f - 0.25f));
            float x1 = tail * w * 1.1f - w * 0.05f, x2 = head * w * 1.1f - w * 0.05f;
            c.drawRoundRect(Math.max(0, x1), 0, Math.min(w, x2), h, h / 2, h / 2, paint);
        }
    }
}
