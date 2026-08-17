package org.einkwiki.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.einkwiki.app.library.ZimBook;
import org.einkwiki.app.library.ZimBookAdapter;
import org.einkwiki.app.library.ZimLibraryStore;
import org.einkwiki.app.reader.ReaderPageKeyMapper;
import org.einkwiki.app.reader.SearchResult;
import org.einkwiki.app.reader.SearchResultAdapter;
import org.einkwiki.app.reader.ZimArchive;
import org.einkwiki.app.reader.ZimWebViewClient;
import org.einkwiki.app.transfer.LanImportServer;
import org.einkwiki.app.update.GitHubReleaseClient;
import org.einkwiki.app.update.SystemUpdateInstaller;
import org.einkwiki.app.update.UpdateCacheCleaner;
import org.einkwiki.app.update.UpdateClient;
import org.einkwiki.app.update.UpdateException;
import org.einkwiki.app.update.UpdatePackageVerifier;
import org.einkwiki.app.update.UpdatePolicy;
import org.einkwiki.app.update.UpdateRelease;
import org.einkwiki.app.update.VerifiedUpdate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-activity, animation-free local ZIM reader for e-ink Android devices. */
public final class MainActivity extends Activity {
    private enum Screen {
        HOME,
        SETTINGS,
        LIBRARY,
        SEARCH,
        READER
    }

    private enum UpdateState {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        DOWNLOADING,
        READY,
        INSTALL_PERMISSION_REQUIRED,
        CHECK_FAILED,
        DOWNLOAD_FAILED,
        INSTALL_FAILED,
        FILE_UNAVAILABLE
    }

    private interface UpdateWork<T> {
        T run() throws Exception;
    }

    private interface UpdateCompletion<T> {
        void complete(T value, Exception error);
    }

    private static final class UpdateCheckResult {
        final String currentVersion;
        final UpdateRelease release;

        UpdateCheckResult(String currentVersion, UpdateRelease release) {
            this.currentVersion = currentVersion;
            this.release = release;
        }
    }

    private static final int SEARCH_LIMIT = 50;
    private static final int TEXT_ZOOM_SMALL = 100;
    private static final int TEXT_ZOOM_MEDIUM = 115;
    private static final int TEXT_ZOOM_LARGE = 130;
    private static final int DEFAULT_TEXT_ZOOM = TEXT_ZOOM_MEDIUM;
    private static final String PREF_READER_TEXT_ZOOM = "reader_text_zoom";
    private static final String PREF_SHOW_PAGE_BUTTONS = "reader_show_page_buttons";
    private static final long RANDOM_REFRESH_MS = 60_000L;
    private static final String UPDATE_CACHE_DIRECTORY = "updates";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "einkwiki-library");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicInteger libraryGeneration = new AtomicInteger();
    private final AtomicInteger archiveGeneration = new AtomicInteger();
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private final List<ZimBook> books = new ArrayList<>();
    private final List<Runnable> archiveReadyCallbacks = new ArrayList<>();

    private ZimLibraryStore libraryStore;
    private ZimBookAdapter bookAdapter;
    private SearchResultAdapter resultAdapter;
    private ZimBook selectedBook;
    private ZimArchive archive;
    private String archiveFileName = "";
    private String openingFileName = "";
    private String deletingFileName = "";
    private LanImportServer importServer;
    private LanImportServer.Snapshot importSnapshot;
    private List<String> importUrls = new ArrayList<>();

    private View homeScreen;
    private View settingsScreen;
    private View libraryScreen;
    private View searchScreen;
    private View readerScreen;
    private Button backButton;
    private Button settingsButton;
    private TextView toolbarTitle;
    private TextView messageBar;
    private EditText homeSearchInput;
    private TextView randomEntriesTitle;
    private final TextView[] randomEntryLabels = new TextView[3];
    private final SearchResult[] randomEntries = new SearchResult[3];
    private Button openLibraryButton;
    private CheckBox showPageButtonsCheckbox;
    private RadioGroup fontSizeGroup;
    private ListView bookList;
    private Button startImportButton;
    private TextView importAddress;
    private TextView importStatus;
    private EInkProgressView importProgress;
    private Button stopImportButton;
    private EditText searchInput;
    private Button searchButton;
    private TextView searchStatus;
    private ListView searchResults;
    private WebView articleWebView;
    private View readerPageControls;
    private TextView currentVersionView;
    private TextView updateStatus;
    private Button updateButton;

    private Screen currentScreen = Screen.HOME;
    private Screen searchReturnScreen = Screen.HOME;
    private Screen readerReturnScreen = Screen.SEARCH;
    private boolean resumed;
    private boolean destroyed;
    private boolean randomLoading;
    private boolean clearHistoryOnPageFinish;
    private int navigationGeneration;
    private int textZoom;
    private boolean showPageButtons;

    private UpdatePackageVerifier updateVerifier;
    private SystemUpdateInstaller updateInstaller;
    private int updateGeneration;
    private UpdateState updateState = UpdateState.IDLE;
    private String installedVersionName = BuildConfig.VERSION_NAME;
    private UpdateRelease availableUpdate;
    private VerifiedUpdate verifiedUpdate;
    private UpdateClient activeUpdateClient;
    private Thread activeUpdateThread;
    private boolean waitingForInstallPermission;

    private final Runnable randomRefresh = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && resumed && currentScreen == Screen.HOME) {
                loadRandomEntries();
                mainHandler.postDelayed(this, RANDOM_REFRESH_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        configureWindow();
        bindViews();

        libraryStore = new ZimLibraryStore(this);
        resultAdapter = new SearchResultAdapter(this);
        searchResults.setAdapter(resultAdapter);
        bookAdapter = new ZimBookAdapter(this, new ZimBookAdapter.Listener() {
            @Override
            public void onPrimary(ZimBook book) {
                handleBookPrimary(book);
            }

            @Override
            public void onDelete(ZimBook book) {
                confirmDeleteBook(book);
            }
        });
        bookList.setAdapter(bookAdapter);

        UpdateCacheCleaner.clearAbandoned(this);
        updateVerifier = new UpdatePackageVerifier(this);
        updateInstaller = new SystemUpdateInstaller(this);
        int savedTextZoom = getPreferences(MODE_PRIVATE)
                .getInt(PREF_READER_TEXT_ZOOM, DEFAULT_TEXT_ZOOM);
        textZoom = nearestTextZoom(savedTextZoom);
        showPageButtons = getPreferences(MODE_PRIVATE)
                .getBoolean(PREF_SHOW_PAGE_BUTTONS, true);

        configureReader();
        renderReaderPreferences();
        bindActions();
        loadInstalledVersionName();
        renderUpdateSection();
        showHome();
        refreshBooks("");
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void bindViews() {
        homeScreen = findViewById(R.id.home_screen);
        settingsScreen = findViewById(R.id.settings_screen);
        libraryScreen = findViewById(R.id.library_screen);
        searchScreen = findViewById(R.id.search_screen);
        readerScreen = findViewById(R.id.reader_screen);
        backButton = findViewById(R.id.back_button);
        settingsButton = findViewById(R.id.settings_button);
        toolbarTitle = findViewById(R.id.toolbar_title);
        messageBar = findViewById(R.id.message_bar);
        homeSearchInput = findViewById(R.id.home_search_input);
        randomEntriesTitle = findViewById(R.id.random_entries_title);
        randomEntryLabels[0] = findViewById(R.id.random_entry_1);
        randomEntryLabels[1] = findViewById(R.id.random_entry_2);
        randomEntryLabels[2] = findViewById(R.id.random_entry_3);

        openLibraryButton = findViewById(R.id.open_library_button);
        showPageButtonsCheckbox = findViewById(R.id.show_page_buttons_checkbox);
        fontSizeGroup = findViewById(R.id.font_size_group);

        bookList = findViewById(R.id.offline_pack_list);
        bookList.setItemsCanFocus(true);
        View libraryHeader = getLayoutInflater().inflate(R.layout.library_header, bookList, false);
        View libraryFooter = getLayoutInflater().inflate(R.layout.library_footer, bookList, false);
        bookList.addHeaderView(libraryHeader, null, false);
        bookList.addFooterView(libraryFooter, null, false);
        startImportButton = libraryHeader.findViewById(R.id.start_import_button);
        importAddress = libraryHeader.findViewById(R.id.import_address);
        importStatus = libraryHeader.findViewById(R.id.import_status);
        importProgress = libraryHeader.findViewById(R.id.import_progress);
        stopImportButton = libraryHeader.findViewById(R.id.stop_import_button);

        searchInput = findViewById(R.id.search_input);
        searchButton = findViewById(R.id.search_button);
        searchStatus = findViewById(R.id.search_status);
        searchResults = findViewById(R.id.search_results);
        articleWebView = findViewById(R.id.article_webview);
        readerPageControls = findViewById(R.id.reader_page_controls);
        currentVersionView = libraryFooter.findViewById(R.id.current_version);
        updateStatus = libraryFooter.findViewById(R.id.update_status);
        updateButton = libraryFooter.findViewById(R.id.update_button);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureReader() {
        articleWebView.setBackgroundColor(Color.WHITE);
        articleWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        articleWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        articleWebView.setHapticFeedbackEnabled(false);
        WebSettings settings = articleWebView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setDefaultFontSize(18);
        settings.setMinimumFontSize(12);
        settings.setTextZoom(textZoom);
        settings.setOffscreenPreRaster(false);
    }

    private void bindActions() {
        backButton.setOnClickListener(view -> handleBack());
        settingsButton.setOnClickListener(view -> showSettings());
        openLibraryButton.setOnClickListener(view -> {
            if (importServer == null) {
                showLibrary();
            }
        });
        messageBar.setOnClickListener(view -> messageBar.setVisibility(View.GONE));
        startImportButton.setOnClickListener(view -> startLanImport());
        stopImportButton.setOnClickListener(view -> stopLanImport());
        updateButton.setOnClickListener(view -> handleUpdateButton());

        homeSearchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (isSearchAction(actionId, event)) {
                performHomeSearch();
                return true;
            }
            return false;
        });
        searchButton.setOnClickListener(view -> performSearch());
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (isSearchAction(actionId, event)) {
                performSearch();
                return true;
            }
            return false;
        });
        searchResults.setOnItemClickListener((parent, view, position, id) ->
                openArticle(resultAdapter.itemAt(position), Screen.SEARCH));
        for (int index = 0; index < randomEntryLabels.length; index++) {
            final int entryIndex = index;
            randomEntryLabels[index].setOnClickListener(view -> {
                SearchResult entry = randomEntries[entryIndex];
                if (entry != null) {
                    openArticle(entry, Screen.HOME);
                }
            });
        }
        findViewById(R.id.page_up_button).setOnClickListener(view -> pageBy(-1));
        findViewById(R.id.page_down_button).setOnClickListener(view -> pageBy(1));
        showPageButtonsCheckbox.setOnCheckedChangeListener((button, checked) -> {
            showPageButtons = checked;
            readerPageControls.setVisibility(checked ? View.VISIBLE : View.GONE);
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(PREF_SHOW_PAGE_BUTTONS, checked)
                    .apply();
        });
        fontSizeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.font_size_small) {
                setTextZoom(TEXT_ZOOM_SMALL);
            } else if (checkedId == R.id.font_size_medium) {
                setTextZoom(TEXT_ZOOM_MEDIUM);
            } else if (checkedId == R.id.font_size_large) {
                setTextZoom(TEXT_ZOOM_LARGE);
            }
        });
    }

    private void renderReaderPreferences() {
        showPageButtonsCheckbox.setChecked(showPageButtons);
        readerPageControls.setVisibility(showPageButtons ? View.VISIBLE : View.GONE);
        if (textZoom == TEXT_ZOOM_SMALL) {
            fontSizeGroup.check(R.id.font_size_small);
        } else if (textZoom == TEXT_ZOOM_LARGE) {
            fontSizeGroup.check(R.id.font_size_large);
        } else {
            fontSizeGroup.check(R.id.font_size_medium);
        }
    }

    private static int nearestTextZoom(int value) {
        int smallDistance = Math.abs(value - TEXT_ZOOM_SMALL);
        int mediumDistance = Math.abs(value - TEXT_ZOOM_MEDIUM);
        int largeDistance = Math.abs(value - TEXT_ZOOM_LARGE);
        if (smallDistance <= mediumDistance && smallDistance <= largeDistance) {
            return TEXT_ZOOM_SMALL;
        }
        return mediumDistance <= largeDistance ? TEXT_ZOOM_MEDIUM : TEXT_ZOOM_LARGE;
    }

    private void setTextZoom(int zoom) {
        if (textZoom == zoom) {
            return;
        }
        textZoom = zoom;
        articleWebView.getSettings().setTextZoom(textZoom);
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_READER_TEXT_ZOOM, textZoom).apply();
    }

    private static boolean isSearchAction(int actionId, KeyEvent event) {
        return actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (waitingForInstallPermission && updateInstaller.canRequestInstall()) {
            waitingForInstallPermission = false;
            updateState = verifiedUpdate != null
                    ? UpdateState.READY
                    : availableUpdate != null ? UpdateState.AVAILABLE : UpdateState.IDLE;
            renderUpdateSection();
        }
        scheduleRandomRefresh();
        updateKeepScreenOn();
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(randomRefresh);
        super.onPause();
    }

    @Override
    protected void onStop() {
        cancelActiveUpdateTask(true);
        super.onStop();
    }

    private void refreshBooks(String preferredFileName) {
        int generation = libraryGeneration.incrementAndGet();
        ioExecutor.execute(() -> {
            List<ZimBook> found = new ArrayList<>();
            int invalidCount = 0;
            try {
                for (File file : libraryStore.scan()) {
                    try {
                        found.add(ZimArchive.inspect(getApplicationContext(), file));
                    } catch (Exception | LinkageError error) {
                        invalidCount++;
                    }
                }
            } catch (IOException error) {
                int finalInvalidCount = invalidCount;
                postToUi(() -> {
                    if (generation == libraryGeneration.get()) {
                        showMessage("无法读取书库：" + readableError(error));
                        if (finalInvalidCount > 0) {
                            showMessage("书库中有无法读取的 ZIM 文件");
                        }
                    }
                });
                return;
            }
            int skipped = invalidCount;
            postToUi(() -> {
                if (generation != libraryGeneration.get()) {
                    return;
                }
                books.clear();
                books.addAll(found);
                String wanted = preferredFileName == null || preferredFileName.isEmpty()
                        ? libraryStore.selectedFileName()
                        : preferredFileName;
                selectedBook = findBook(wanted);
                if (selectedBook == null && !books.isEmpty()) {
                    selectedBook = books.get(0);
                    try {
                        libraryStore.select(selectedBook);
                    } catch (IOException error) {
                        showMessage(readableError(error));
                    }
                }
                renderBooks();
                if (skipped > 0) {
                    showMessage("已忽略 " + skipped + " 个无法读取的 ZIM 文件");
                }
                if (currentScreen == Screen.HOME) {
                    scheduleRandomRefresh();
                }
            });
        });
    }

    private ZimBook findBook(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        for (ZimBook book : books) {
            if (fileName.equals(book.fileName)) {
                return book;
            }
        }
        return null;
    }

    private void renderBooks() {
        String selected = selectedBook == null ? "" : selectedBook.fileName;
        bookAdapter.submit(books, selected, deletingFileName);
    }

    private void handleBookPrimary(ZimBook book) {
        if (importServer != null) {
            showMessage("请先停止局域网导入");
            return;
        }
        if (selectedBook != null && selectedBook.fileName.equals(book.fileName)) {
            openSearch(book, Screen.LIBRARY, false);
            return;
        }
        try {
            libraryStore.select(book);
            selectedBook = book;
            invalidateArchiveSession();
            renderBooks();
            showMessage("已将《" + book.title + "》设为当前搜索库");
        } catch (IOException error) {
            showMessage(readableError(error));
        }
    }

    private void confirmDeleteBook(ZimBook book) {
        if (importServer != null) {
            showMessage("请先停止局域网导入");
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_remove_title)
                .setMessage("将从本机删除《" + book.title + "》。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (ignored, which) -> deleteBook(book))
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(0);
        }
        configureDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        configureDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
    }

    private void configureDialogButton(Button button) {
        if (button == null) {
            return;
        }
        button.setAllCaps(false);
        button.setTextColor(getColor(R.color.ink_black));
        button.setBackgroundResource(R.drawable.button_background);
        button.setStateListAnimator(null);
        button.setMinHeight(getResources().getDimensionPixelSize(R.dimen.touch_target));
    }

    private void deleteBook(ZimBook book) {
        deletingFileName = book.fileName;
        renderBooks();
        ZimArchive toClose = detachArchiveIf(book.fileName);
        ioExecutor.execute(() -> {
            if (toClose != null) {
                toClose.close();
            }
            try {
                libraryStore.delete(book);
                postToUi(() -> {
                    deletingFileName = "";
                    refreshBooks("");
                });
            } catch (IOException error) {
                postToUi(() -> {
                    deletingFileName = "";
                    renderBooks();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void startLanImport() {
        if (importServer != null || activeUpdateThread != null) {
            return;
        }
        LanImportServer[] holder = new LanImportServer[1];
        LanImportServer server = new LanImportServer(
                this,
                libraryStore,
                new LanImportServer.Listener() {
                    @Override
                    public void onSnapshot(LanImportServer.Snapshot snapshot) {
                        postToUi(() -> {
                            if (importServer == holder[0]) {
                                importSnapshot = snapshot;
                                renderImportSection();
                            }
                        });
                    }

                    @Override
                    public void onImported(File file) {
                        postToUi(() -> {
                            refreshBooks(file.getName());
                            showMessage("ZIM 已导入并设为当前搜索库");
                        });
                    }
                }
        );
        holder[0] = server;
        try {
            importServer = server;
            importUrls = server.start();
            importSnapshot = server.snapshot();
            renderImportSection();
        } catch (IOException error) {
            server.close();
            importServer = null;
            showMessage("无法启动局域网导入：" + readableError(error));
            renderImportSection();
        }
    }

    private void stopLanImport() {
        LanImportServer server = importServer;
        importServer = null;
        importUrls = new ArrayList<>();
        importSnapshot = null;
        if (server != null) {
            server.close();
        }
        renderImportSection();
    }

    private void renderImportSection() {
        boolean active = importServer != null;
        startImportButton.setEnabled(!active && activeUpdateThread == null);
        importAddress.setVisibility(active ? View.VISIBLE : View.GONE);
        importStatus.setVisibility(active ? View.VISIBLE : View.GONE);
        stopImportButton.setVisibility(active ? View.VISIBLE : View.GONE);
        importProgress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) {
            importProgress.setProgress(0);
            renderUpdateSection();
            updateKeepScreenOn();
            return;
        }
        importAddress.setText(getString(
                R.string.import_address_format,
                android.text.TextUtils.join("\n", importUrls)
        ));
        LanImportServer.Snapshot snapshot = importSnapshot;
        if (snapshot == null) {
            importStatus.setText(R.string.import_waiting);
            importProgress.setProgress(0);
        } else {
            String detail = snapshot.message;
            if (snapshot.state == LanImportServer.State.RECEIVING) {
                detail += "\n" + ZimLibraryStore.formatBytes(snapshot.receivedBytes)
                        + " / " + ZimLibraryStore.formatBytes(snapshot.totalBytes);
                if (snapshot.bytesPerSecond > 0L) {
                    detail += " · " + ZimLibraryStore.formatBytes(snapshot.bytesPerSecond) + "/s";
                }
            }
            importStatus.setText(detail);
            importProgress.setProgress(snapshot.percent());
        }
        renderUpdateSection();
        updateKeepScreenOn();
    }

    private void performHomeSearch() {
        String term = homeSearchInput.getText().toString().trim();
        if (term.isEmpty()) {
            return;
        }
        if (selectedBook == null) {
            showMessage(getString(R.string.home_pack_required));
            return;
        }
        searchInput.setText(term);
        searchInput.setSelection(term.length());
        openSearch(selectedBook, Screen.HOME, true);
    }

    private void openSearch(ZimBook book, Screen returnScreen, boolean searchImmediately) {
        Screen requestScreen = currentScreen;
        int navigationAtRequest = navigationGeneration;
        hideKeyboard();
        showMessage(getString(R.string.opening_offline_pack));
        ensureArchive(book, () -> {
            if (currentScreen != requestScreen || navigationGeneration != navigationAtRequest) {
                return;
            }
            searchReturnScreen = returnScreen;
            messageBar.setVisibility(View.GONE);
            showSearch();
            if (searchImmediately) {
                performSearch();
            }
        });
    }

    private void ensureArchive(ZimBook book, Runnable onReady) {
        if (archive != null && book.fileName.equals(archiveFileName)) {
            onReady.run();
            return;
        }
        if (book.fileName.equals(openingFileName)) {
            archiveReadyCallbacks.add(onReady);
            return;
        }

        int generation = archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        openingFileName = book.fileName;
        archiveReadyCallbacks.clear();
        archiveReadyCallbacks.add(onReady);
        homeSearchInput.setEnabled(false);
        ZimArchive previous = detachArchive();
        ioExecutor.execute(() -> {
            if (previous != null) {
                previous.close();
            }
            ZimArchive opened;
            try {
                opened = ZimArchive.open(getApplicationContext(), libraryStore.file(book.fileName));
            } catch (Exception | LinkageError error) {
                postToUi(() -> {
                    if (generation == archiveGeneration.get()) {
                        openingFileName = "";
                        archiveReadyCallbacks.clear();
                        randomLoading = false;
                        homeSearchInput.setEnabled(true);
                        showMessage("无法打开 ZIM：" + readableError(error));
                    }
                });
                return;
            }
            postToUi(() -> {
                if (generation != archiveGeneration.get()) {
                    opened.close();
                    return;
                }
                archive = opened;
                archiveFileName = book.fileName;
                openingFileName = "";
                homeSearchInput.setEnabled(true);
                attachArchiveToWebView();
                List<Runnable> callbacks = new ArrayList<>(archiveReadyCallbacks);
                archiveReadyCallbacks.clear();
                for (Runnable callback : callbacks) {
                    callback.run();
                }
            });
        });
    }

    private ZimArchive detachArchive() {
        if (archive == null) {
            archiveFileName = "";
            return null;
        }
        articleWebView.stopLoading();
        articleWebView.setWebViewClient(new WebViewClient());
        articleWebView.loadUrl("about:blank");
        resultAdapter.replace(null);
        ZimArchive detached = archive;
        archive = null;
        archiveFileName = "";
        return detached;
    }

    private ZimArchive detachArchiveIf(String fileName) {
        if (!fileName.equals(archiveFileName) && !fileName.equals(openingFileName)) {
            return null;
        }
        archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        openingFileName = "";
        archiveReadyCallbacks.clear();
        homeSearchInput.setEnabled(true);
        return detachArchive();
    }

    private void invalidateArchiveSession() {
        archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        openingFileName = "";
        archiveReadyCallbacks.clear();
        homeSearchInput.setEnabled(true);
        ZimArchive toClose = detachArchive();
        if (toClose != null) {
            ioExecutor.execute(toClose::close);
        }
        hideRandomEntries();
    }

    private void attachArchiveToWebView() {
        ZimArchive attached = archive;
        articleWebView.setWebViewClient(new ZimWebViewClient(
                this,
                attached,
                new ZimWebViewClient.Listener() {
                    @Override
                    public void onExternalLinkBlocked() {
                        showMessage(getString(R.string.external_link_blocked));
                    }

                    @Override
                    public void onPageStarted() {
                        // No transient animation or loading effect on e-ink.
                    }

                    @Override
                    public void onPageFinished(String title) {
                        if (clearHistoryOnPageFinish) {
                            articleWebView.clearHistory();
                            clearHistoryOnPageFinish = false;
                        }
                        if (currentScreen == Screen.READER && title != null && !title.isEmpty()) {
                            toolbarTitle.setText(title);
                        }
                    }

                    @Override
                    public void onMainFrameError() {
                        showMessage(getString(R.string.reader_load_failed));
                    }
                }
        ));
    }

    private void performSearch() {
        String term = searchInput.getText().toString().trim();
        if (term.isEmpty()) {
            searchStatus.setText(R.string.search_empty);
            resultAdapter.replace(null);
            return;
        }
        ZimArchive searchArchive = archive;
        String searchFile = archiveFileName;
        if (searchArchive == null) {
            showMessage("ZIM 尚未打开");
            return;
        }
        hideKeyboard();
        int generation = searchGeneration.incrementAndGet();
        searchButton.setEnabled(false);
        resultAdapter.replace(null);
        searchResults.setSelection(0);
        searchStatus.setText(R.string.searching);
        ioExecutor.execute(() -> {
            List<SearchResult> results;
            try {
                results = searchArchive.search(term, SEARCH_LIMIT);
            } catch (RuntimeException error) {
                postToUi(() -> {
                    if (generation == searchGeneration.get()
                            && searchArchive == archive
                            && searchFile.equals(archiveFileName)) {
                        searchButton.setEnabled(true);
                        searchStatus.setText(getString(
                                R.string.search_failed_format,
                                readableError(error)
                        ));
                    }
                });
                return;
            }
            postToUi(() -> {
                if (generation != searchGeneration.get()
                        || searchArchive != archive
                        || !searchFile.equals(archiveFileName)) {
                    return;
                }
                searchButton.setEnabled(true);
                resultAdapter.replace(results);
                searchResults.setSelection(0);
                searchStatus.setText(results.isEmpty()
                        ? getString(R.string.search_no_results)
                        : getResources().getQuantityString(
                                R.plurals.search_result_count,
                                results.size(),
                                results.size()
                        ));
            });
        });
    }

    private void loadRandomEntries() {
        if (randomLoading || selectedBook == null || currentScreen != Screen.HOME) {
            if (selectedBook == null) {
                hideRandomEntries();
            }
            return;
        }
        randomLoading = true;
        ZimBook target = selectedBook;
        ensureArchive(target, () -> {
            ZimArchive randomArchive = archive;
            String randomFile = archiveFileName;
            ioExecutor.execute(() -> {
                List<SearchResult> entries;
                try {
                    entries = randomArchive.randomEntries(randomEntryLabels.length);
                } catch (RuntimeException error) {
                    entries = new ArrayList<>();
                }
                List<SearchResult> completed = entries;
                postToUi(() -> {
                    randomLoading = false;
                    if (currentScreen == Screen.HOME
                            && target == selectedBook
                            && randomArchive == archive
                            && randomFile.equals(archiveFileName)) {
                        showRandomEntries(completed);
                    }
                });
            });
        });
    }

    private void showRandomEntries(List<SearchResult> entries) {
        randomEntriesTitle.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        for (int index = 0; index < randomEntryLabels.length; index++) {
            SearchResult entry = index < entries.size() ? entries.get(index) : null;
            randomEntries[index] = entry;
            randomEntryLabels[index].setVisibility(entry == null ? View.GONE : View.VISIBLE);
            if (entry != null) {
                randomEntryLabels[index].setText(entry.title);
            }
        }
    }

    private void hideRandomEntries() {
        randomEntriesTitle.setVisibility(View.GONE);
        for (int index = 0; index < randomEntryLabels.length; index++) {
            randomEntries[index] = null;
            randomEntryLabels[index].setVisibility(View.GONE);
        }
    }

    private void scheduleRandomRefresh() {
        mainHandler.removeCallbacks(randomRefresh);
        if (resumed && currentScreen == Screen.HOME) {
            mainHandler.post(randomRefresh);
        }
    }

    private void openArticle(SearchResult result, Screen returnScreen) {
        if (archive == null || result == null || result.path.isEmpty()) {
            return;
        }
        readerReturnScreen = returnScreen;
        hideKeyboard();
        messageBar.setVisibility(View.GONE);
        navigationGeneration++;
        currentScreen = Screen.READER;
        updateKeepScreenOn();
        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.GONE);
        toolbarTitle.setText(result.title);
        clearHistoryOnPageFinish = true;
        articleWebView.loadUrl(archive.contentUrl(result.path));
    }

    private void showHome() {
        navigationGeneration++;
        currentScreen = Screen.HOME;
        homeScreen.setVisibility(View.VISIBLE);
        settingsScreen.setVisibility(View.GONE);
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.GONE);
        settingsButton.setVisibility(View.VISIBLE);
        toolbarTitle.setText(R.string.app_name);
        homeSearchInput.setEnabled(openingFileName.isEmpty());
        homeScreen.requestFocus();
        hideKeyboard();
        scheduleRandomRefresh();
        updateKeepScreenOn();
    }

    private void showSettings() {
        navigationGeneration++;
        currentScreen = Screen.SETTINGS;
        mainHandler.removeCallbacks(randomRefresh);
        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.VISIBLE);
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.GONE);
        toolbarTitle.setText(R.string.settings_title);
        renderReaderPreferences();
        hideKeyboard();
        updateKeepScreenOn();
    }

    private void showLibrary() {
        navigationGeneration++;
        currentScreen = Screen.LIBRARY;
        mainHandler.removeCallbacks(randomRefresh);
        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        libraryScreen.setVisibility(View.VISIBLE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.GONE);
        toolbarTitle.setText(R.string.library_title);
        hideKeyboard();
        refreshBooks("");
        renderImportSection();
        updateKeepScreenOn();
    }

    private void showSearch() {
        navigationGeneration++;
        currentScreen = Screen.SEARCH;
        mainHandler.removeCallbacks(randomRefresh);
        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.VISIBLE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.GONE);
        toolbarTitle.setText(R.string.search_title);
        updateKeepScreenOn();
    }

    private void handleBack() {
        if (importServer != null) {
            showMessage("请先点击“停止导入”");
            return;
        }
        if (currentScreen == Screen.READER) {
            if (articleWebView.canGoBack()) {
                articleWebView.goBack();
            } else if (readerReturnScreen == Screen.HOME) {
                showHome();
            } else {
                showSearch();
            }
        } else if (currentScreen == Screen.SEARCH) {
            if (searchReturnScreen == Screen.LIBRARY) {
                showLibrary();
            } else {
                showHome();
            }
        } else if (currentScreen == Screen.LIBRARY) {
            showSettings();
        } else if (currentScreen == Screen.SETTINGS) {
            showHome();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (currentScreen == Screen.READER) {
            int direction = ReaderPageKeyMapper.directionFor(keyCode);
            if (direction != 0) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    pageBy(direction);
                }
                return true;
            }
        }
        if (currentScreen == Screen.LIBRARY
                && (keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                pageLibraryBy(keyCode == KeyEvent.KEYCODE_PAGE_UP ? -1 : 1);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void updateKeepScreenOn() {
        boolean keep = currentScreen == Screen.READER
                || importServer != null
                || updateState == UpdateState.DOWNLOADING;
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void pageBy(int direction) {
        int overlap = Math.round(48 * getResources().getDisplayMetrics().density);
        int distance = Math.max(1, articleWebView.getHeight() - overlap);
        articleWebView.scrollBy(0, direction * distance);
    }

    private void pageLibraryBy(int direction) {
        int overlap = Math.round(48 * getResources().getDisplayMetrics().density);
        int distance = Math.max(1, bookList.getHeight() - overlap);
        bookList.scrollListBy(direction * distance);
    }

    private void loadInstalledVersionName() {
        try {
            installedVersionName = updateVerifier.currentVersionName();
        } catch (UpdateException ignored) {
            installedVersionName = BuildConfig.VERSION_NAME;
        }
    }

    private void handleUpdateButton() {
        if (BuildConfig.DEBUG || activeUpdateThread != null || importServer != null) {
            return;
        }
        switch (updateState) {
            case AVAILABLE:
            case DOWNLOAD_FAILED:
                downloadUpdate();
                break;
            case READY:
                installVerifiedUpdate();
                break;
            case INSTALL_PERMISSION_REQUIRED:
            case INSTALL_FAILED:
                if (verifiedUpdate == null) {
                    downloadUpdate();
                } else {
                    installVerifiedUpdate();
                }
                break;
            case CHECKING:
            case DOWNLOADING:
                break;
            default:
                checkForUpdates();
                break;
        }
    }

    private void renderUpdateSection() {
        if (currentVersionView == null) {
            return;
        }
        updateKeepScreenOn();
        currentVersionView.setText(getString(R.string.current_version, installedVersionName));
        if (BuildConfig.DEBUG) {
            updateStatus.setVisibility(View.VISIBLE);
            updateStatus.setText(R.string.debug_update_disabled);
            updateButton.setText(R.string.check_for_updates);
            updateButton.setEnabled(false);
            return;
        }

        int statusText = 0;
        switch (updateState) {
            case CHECKING:
                statusText = R.string.checking_for_updates;
                break;
            case UP_TO_DATE:
                statusText = R.string.up_to_date;
                break;
            case DOWNLOADING:
                statusText = R.string.downloading_update;
                break;
            case INSTALL_PERMISSION_REQUIRED:
                statusText = verifiedUpdate == null
                        ? R.string.allow_update_downloads
                        : R.string.allow_update_installs;
                break;
            case CHECK_FAILED:
                statusText = R.string.update_check_failed;
                break;
            case DOWNLOAD_FAILED:
                statusText = R.string.update_download_failed;
                break;
            case INSTALL_FAILED:
                statusText = R.string.installer_unavailable;
                break;
            case FILE_UNAVAILABLE:
                statusText = R.string.update_file_unavailable;
                break;
            case AVAILABLE:
                updateStatus.setText(getString(
                        R.string.update_available,
                        availableUpdate == null ? "" : availableUpdate.versionName()
                ));
                break;
            case READY:
                updateStatus.setText(getString(
                        R.string.update_ready,
                        verifiedUpdate == null ? "" : verifiedUpdate.release().versionName()
                ));
                break;
            case IDLE:
            default:
                break;
        }
        if (updateState == UpdateState.IDLE) {
            updateStatus.setText("");
            updateStatus.setVisibility(View.GONE);
        } else {
            updateStatus.setVisibility(View.VISIBLE);
            if (statusText != 0) {
                updateStatus.setText(statusText);
            }
        }

        int actionText;
        switch (updateState) {
            case AVAILABLE:
            case DOWNLOAD_FAILED:
                actionText = R.string.download_update;
                break;
            case READY:
                actionText = R.string.install_update;
                break;
            case INSTALL_PERMISSION_REQUIRED:
            case INSTALL_FAILED:
                actionText = verifiedUpdate == null
                        ? R.string.allow_install_permission
                        : R.string.install_update;
                break;
            case CHECKING:
                actionText = R.string.checking_for_updates;
                break;
            case DOWNLOADING:
                actionText = R.string.downloading_update;
                break;
            default:
                actionText = R.string.check_for_updates;
                break;
        }
        updateButton.setText(actionText);
        updateButton.setEnabled(importServer == null
                && activeUpdateThread == null
                && updateState != UpdateState.CHECKING
                && updateState != UpdateState.DOWNLOADING);
        if (startImportButton != null) {
            startImportButton.setEnabled(importServer == null && activeUpdateThread == null);
        }
    }

    private void checkForUpdates() {
        if (activeUpdateThread != null) {
            return;
        }
        availableUpdate = null;
        verifiedUpdate = null;
        UpdateCacheCleaner.clearAbandoned(this);
        GitHubReleaseClient client = new GitHubReleaseClient();
        startUpdateTask(
                UpdateState.CHECKING,
                client,
                () -> {
                    String currentVersion = updateVerifier.currentVersionName();
                    if (!currentVersion.equals(UpdatePolicy.normalizedVersion(currentVersion))) {
                        throw new UpdateException("Installed version name is not semantic");
                    }
                    return new UpdateCheckResult(currentVersion, client.latestRelease());
                },
                (result, error) -> {
                    if (error != null || result == null) {
                        updateState = UpdateState.CHECK_FAILED;
                    } else {
                        installedVersionName = result.currentVersion;
                        if (UpdatePolicy.isNewer(result.release.versionName(), result.currentVersion)) {
                            availableUpdate = result.release;
                            updateState = UpdateState.AVAILABLE;
                        } else {
                            updateState = UpdateState.UP_TO_DATE;
                        }
                    }
                    renderUpdateSection();
                }
        );
    }

    private void downloadUpdate() {
        if (activeUpdateThread != null || importServer != null) {
            return;
        }
        UpdateRelease release = availableUpdate;
        if (release == null) {
            updateState = UpdateState.FILE_UNAVAILABLE;
            renderUpdateSection();
            return;
        }
        if (!updateInstaller.canRequestInstall()) {
            waitingForInstallPermission = true;
            updateState = UpdateState.INSTALL_PERMISSION_REQUIRED;
            renderUpdateSection();
            try {
                startActivityWithoutAnimation(updateInstaller.permissionIntent());
            } catch (ActivityNotFoundException | SecurityException error) {
                waitingForInstallPermission = false;
                updateState = UpdateState.INSTALL_FAILED;
                renderUpdateSection();
            }
            return;
        }
        verifiedUpdate = null;
        GitHubReleaseClient client = new GitHubReleaseClient();
        startUpdateTask(
                UpdateState.DOWNLOADING,
                client,
                () -> {
                    File downloaded = null;
                    try {
                        downloaded = client.download(
                                release,
                                new File(getCacheDir(), UPDATE_CACHE_DIRECTORY)
                        );
                        return updateVerifier.verify(downloaded, release);
                    } catch (Exception error) {
                        if (downloaded != null) {
                            //noinspection ResultOfMethodCallIgnored
                            downloaded.delete();
                        }
                        throw error;
                    }
                },
                (verified, error) -> {
                    if (error != null || verified == null) {
                        updateState = UpdateState.DOWNLOAD_FAILED;
                    } else {
                        verifiedUpdate = verified;
                        updateState = UpdateState.READY;
                    }
                    renderUpdateSection();
                }
        );
    }

    private void installVerifiedUpdate() {
        VerifiedUpdate verified = verifiedUpdate;
        if (verified == null) {
            updateState = UpdateState.FILE_UNAVAILABLE;
            renderUpdateSection();
            return;
        }
        try {
            updateVerifier.verify(verified.file(), verified.release());
        } catch (UpdateException error) {
            //noinspection ResultOfMethodCallIgnored
            verified.file().delete();
            verifiedUpdate = null;
            updateState = UpdateState.FILE_UNAVAILABLE;
            renderUpdateSection();
            return;
        }
        if (!updateInstaller.canRequestInstall()) {
            waitingForInstallPermission = true;
            updateState = UpdateState.INSTALL_PERMISSION_REQUIRED;
            renderUpdateSection();
            try {
                startActivityWithoutAnimation(updateInstaller.permissionIntent());
            } catch (ActivityNotFoundException | SecurityException error) {
                waitingForInstallPermission = false;
                updateState = UpdateState.INSTALL_FAILED;
                renderUpdateSection();
            }
            return;
        }
        try {
            startActivityWithoutAnimation(updateInstaller.installIntent(verified.file()));
        } catch (ActivityNotFoundException | SecurityException | UpdateException error) {
            updateState = UpdateState.INSTALL_FAILED;
            renderUpdateSection();
        }
    }

    private void startActivityWithoutAnimation(Intent intent) {
        Bundle options = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
        startActivity(intent, options);
        //noinspection deprecation
        overridePendingTransition(0, 0);
    }

    private <T> void startUpdateTask(
            UpdateState busyState,
            UpdateClient client,
            UpdateWork<T> work,
            UpdateCompletion<T> completion
    ) {
        cancelActiveUpdateTask(false);
        updateState = busyState;
        int generation = ++updateGeneration;
        activeUpdateClient = client;
        Thread worker = new Thread(() -> {
            T value = null;
            Exception failure = null;
            try {
                value = work.run();
            } catch (Exception error) {
                failure = error;
            }
            T completedValue = value;
            Exception completedFailure = failure;
            Thread completedWorker = Thread.currentThread();
            postToUi(() -> {
                if (activeUpdateThread == completedWorker) {
                    activeUpdateThread = null;
                }
                if (generation != updateGeneration) {
                    renderUpdateSection();
                    return;
                }
                activeUpdateClient = null;
                completion.complete(completedValue, completedFailure);
            });
        }, "einkwiki-update");
        activeUpdateThread = worker;
        renderUpdateSection();
        worker.start();
    }

    private void cancelActiveUpdateTask(boolean restoreIdleState) {
        UpdateClient client = activeUpdateClient;
        Thread worker = activeUpdateThread;
        if (client == null && worker == null) {
            return;
        }
        updateGeneration++;
        if (client != null) {
            client.cancel();
        }
        if (worker != null) {
            worker.interrupt();
        }
        activeUpdateClient = null;
        if (restoreIdleState) {
            if (updateState == UpdateState.CHECKING) {
                updateState = UpdateState.IDLE;
            } else if (updateState == UpdateState.DOWNLOADING) {
                updateState = availableUpdate == null
                        ? UpdateState.IDLE
                        : UpdateState.AVAILABLE;
            }
            if (!destroyed) {
                renderUpdateSection();
            }
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private void postToUi(Runnable action) {
        mainHandler.post(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    private void showMessage(String message) {
        messageBar.setText(message == null || message.isEmpty() ? "操作失败" : message);
        messageBar.setVisibility(View.VISIBLE);
    }

    private static String readableError(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    @Override
    protected void onDestroy() {
        cancelActiveUpdateTask(false);
        destroyed = true;
        resumed = false;
        mainHandler.removeCallbacksAndMessages(null);
        libraryGeneration.incrementAndGet();
        archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        LanImportServer server = importServer;
        importServer = null;
        if (server != null) {
            server.close();
        }
        articleWebView.stopLoading();
        articleWebView.setWebViewClient(new WebViewClient());
        articleWebView.destroy();
        ZimArchive toClose = archive;
        archive = null;
        archiveFileName = "";
        if (toClose != null) {
            ioExecutor.execute(toClose::close);
        }
        ioExecutor.shutdown();
        super.onDestroy();
    }
}
