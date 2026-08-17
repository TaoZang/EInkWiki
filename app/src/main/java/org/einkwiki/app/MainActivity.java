package org.einkwiki.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.einkwiki.app.data.OfflinePack;
import org.einkwiki.app.data.OfflinePackStore;
import org.einkwiki.app.download.DownloadSnapshot;
import org.einkwiki.app.download.PackDownloadManager;
import org.einkwiki.app.reader.KiwixArchive;
import org.einkwiki.app.reader.PackVerifier;
import org.einkwiki.app.reader.SearchResult;
import org.einkwiki.app.reader.SearchResultAdapter;
import org.einkwiki.app.reader.ZimWebViewClient;
import org.einkwiki.app.update.GitHubReleaseClient;
import org.einkwiki.app.update.SystemUpdateInstaller;
import org.einkwiki.app.update.UpdateClient;
import org.einkwiki.app.update.UpdateCacheCleaner;
import org.einkwiki.app.update.UpdateException;
import org.einkwiki.app.update.UpdatePackageVerifier;
import org.einkwiki.app.update.UpdatePolicy;
import org.einkwiki.app.update.UpdateRelease;
import org.einkwiki.app.update.VerifiedUpdate;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-screen, animation-free offline Wikipedia reader for e-ink Android devices. */
public final class MainActivity extends Activity {
    private enum Screen {
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

    private static final long DOWNLOAD_POLL_MS = 3_000L;
    private static final int SEARCH_LIMIT = 50;
    private static final int DEFAULT_TEXT_ZOOM = 115;
    private static final int MIN_TEXT_ZOOM = 90;
    private static final int MAX_TEXT_ZOOM = 150;
    private static final String UPDATE_CACHE_DIRECTORY = "updates";

    private final OfflinePack pack = OfflinePack.DEVELOPMENT;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "einkwiki-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private final AtomicInteger archiveGeneration = new AtomicInteger();

    private OfflinePackStore packStore;
    private PackDownloadManager packDownloads;
    private KiwixArchive archive;
    private SearchResultAdapter resultAdapter;
    private UpdatePackageVerifier updateVerifier;
    private SystemUpdateInstaller updateInstaller;

    private View libraryScreen;
    private View searchScreen;
    private View readerScreen;
    private Button backButton;
    private Button libraryButton;
    private TextView toolbarTitle;
    private TextView messageBar;
    private TextView storageStatus;
    private TextView downloadDetail;
    private EInkProgressView downloadProgress;
    private Button downloadButton;
    private Button openSearchButton;
    private Button removePackButton;
    private EditText searchInput;
    private Button searchButton;
    private TextView searchStatus;
    private ListView searchResults;
    private WebView articleWebView;
    private TextView currentVersionView;
    private TextView updateStatus;
    private Button updateButton;

    private Screen currentScreen = Screen.LIBRARY;
    private boolean resumed;
    private boolean destroyed;
    private boolean verificationRunning;
    private boolean invalidLocalData;
    private boolean invalidDataIsInstalledCandidate;
    private boolean invalidDataCanRetryRegistry;
    private boolean clearHistoryOnPageFinish;
    private int lastRenderedProgress = -1;
    private int textZoom;
    private int updateGeneration;
    private UpdateState updateState = UpdateState.IDLE;
    private String installedVersionName = BuildConfig.VERSION_NAME;
    private UpdateRelease availableUpdate;
    private VerifiedUpdate verifiedUpdate;
    private UpdateClient activeUpdateClient;
    private Thread activeUpdateThread;
    private boolean waitingForInstallPermission;

    private final Runnable downloadPoll = new Runnable() {
        @Override
        public void run() {
            if (!resumed || destroyed) {
                return;
            }
            refreshLibraryState();
            DownloadSnapshot snapshot = packDownloads.query();
            if (snapshot.isActive()) {
                mainHandler.postDelayed(this, DOWNLOAD_POLL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        configureWindow();
        bindViews();

        packStore = new OfflinePackStore(this);
        packDownloads = new PackDownloadManager(this, packStore);
        UpdateCacheCleaner.clearAbandoned(this);
        updateVerifier = new UpdatePackageVerifier(this);
        updateInstaller = new SystemUpdateInstaller(this);
        resultAdapter = new SearchResultAdapter(this);
        searchResults.setAdapter(resultAdapter);
        textZoom = getPreferences(MODE_PRIVATE).getInt("reader_text_zoom", DEFAULT_TEXT_ZOOM);

        configureReader();
        bindActions();
        loadInstalledVersionName();
        renderUpdateSection();
        showLibrary();
        refreshLibraryState();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        int systemUiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(systemUiFlags);
    }

    private void bindViews() {
        libraryScreen = findViewById(R.id.library_screen);
        searchScreen = findViewById(R.id.search_screen);
        readerScreen = findViewById(R.id.reader_screen);
        backButton = findViewById(R.id.back_button);
        libraryButton = findViewById(R.id.library_button);
        toolbarTitle = findViewById(R.id.toolbar_title);
        messageBar = findViewById(R.id.message_bar);
        storageStatus = findViewById(R.id.storage_status);
        downloadDetail = findViewById(R.id.download_detail);
        downloadProgress = findViewById(R.id.download_progress);
        downloadButton = findViewById(R.id.download_button);
        openSearchButton = findViewById(R.id.open_search_button);
        removePackButton = findViewById(R.id.remove_pack_button);
        searchInput = findViewById(R.id.search_input);
        searchButton = findViewById(R.id.search_button);
        searchStatus = findViewById(R.id.search_status);
        searchResults = findViewById(R.id.search_results);
        articleWebView = findViewById(R.id.article_webview);
        currentVersionView = findViewById(R.id.current_version);
        updateStatus = findViewById(R.id.update_status);
        updateButton = findViewById(R.id.update_button);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureReader() {
        articleWebView.setBackgroundColor(Color.WHITE);
        articleWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        articleWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        articleWebView.setHapticFeedbackEnabled(false);
        articleWebView.setLongClickable(true);

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
        settings.setTextZoom(textZoom == 0 ? DEFAULT_TEXT_ZOOM : textZoom);
        settings.setOffscreenPreRaster(false);
    }

    private void bindActions() {
        backButton.setOnClickListener(view -> handleBack());
        messageBar.setOnClickListener(view -> messageBar.setVisibility(View.GONE));
        libraryButton.setOnClickListener(view -> showLibrary());
        downloadButton.setOnClickListener(view -> handleDownloadButton());
        openSearchButton.setOnClickListener(view -> openSearch());
        removePackButton.setOnClickListener(view -> confirmRemovePack());
        updateButton.setOnClickListener(view -> handleUpdateButton());
        searchButton.setOnClickListener(view -> performSearch());
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch();
                return true;
            }
            return false;
        });
        searchResults.setOnItemClickListener((parent, view, position, id) -> {
            SearchResult result = resultAdapter.itemAt(position);
            openArticle(result);
        });
        findViewById(R.id.page_up_button).setOnClickListener(view -> pageBy(-1));
        findViewById(R.id.page_down_button).setOnClickListener(view -> pageBy(1));
        findViewById(R.id.font_smaller_button).setOnClickListener(view -> adjustTextZoom(-10));
        findViewById(R.id.font_larger_button).setOnClickListener(view -> adjustTextZoom(10));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (waitingForInstallPermission
                && updateInstaller.canRequestInstall()) {
            waitingForInstallPermission = false;
            if (verifiedUpdate != null) {
                updateState = UpdateState.READY;
            } else if (availableUpdate != null) {
                updateState = UpdateState.AVAILABLE;
            } else {
                updateState = UpdateState.IDLE;
            }
            renderUpdateSection();
        }
        refreshLibraryState();
        scheduleDownloadPoll();
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(downloadPoll);
        super.onPause();
    }

    @Override
    protected void onStop() {
        cancelActiveUpdateTask(true);
        super.onStop();
    }

    private void scheduleDownloadPoll() {
        mainHandler.removeCallbacks(downloadPoll);
        if (packDownloads.query().isActive()) {
            mainHandler.postDelayed(downloadPoll, DOWNLOAD_POLL_MS);
        }
    }

    private void refreshLibraryState() {
        if (verificationRunning) {
            renderVerifying();
            return;
        }
        if (packStore.isInstalled(pack)) {
            // Recovers a crash between activation and removing the successful system row.
            if (packDownloads.trackedId() != -1L) {
                packDownloads.removeCompletedRecord();
            }
            invalidLocalData = false;
            invalidDataIsInstalledCandidate = false;
            invalidDataCanRetryRegistry = false;
            renderReady();
            return;
        }
        if (packStore.hasInstalledCandidate(pack) && !invalidLocalData) {
            verifyExistingCandidate();
            return;
        }
        if (invalidLocalData) {
            renderInvalidLocalData();
            return;
        }

        DownloadSnapshot snapshot = packDownloads.query();
        switch (snapshot.state) {
            case PENDING:
            case RUNNING:
            case PAUSED:
                renderActiveDownload(snapshot);
                break;
            case SUCCESSFUL:
                verifyCompletedDownload();
                break;
            case FAILED:
                renderDownloadFailure(snapshot.reason);
                break;
            case NONE:
            default:
                if (packDownloads.trackedId() != -1L) {
                    packDownloads.clearTracking();
                }
                renderAvailable();
                break;
        }
    }

    private void handleDownloadButton() {
        DownloadSnapshot snapshot = packDownloads.query();
        if (snapshot.isActive()) {
            packDownloads.cancelTrackedDownload();
            try {
                packStore.clearPartial(pack);
            } catch (IOException ignored) {
                // DownloadManager may still be completing its own exact-file cleanup.
            }
            invalidLocalData = false;
            renderAvailable();
            return;
        }

        if (invalidLocalData) {
            if (invalidDataIsInstalledCandidate && invalidDataCanRetryRegistry) {
                invalidLocalData = false;
                invalidDataIsInstalledCandidate = false;
                invalidDataCanRetryRegistry = false;
                verifyExistingCandidate();
                return;
            }
            try {
                if (invalidDataIsInstalledCandidate) {
                    if (!packStore.deleteInstalled(pack)) {
                        showMessage("文件仍在使用，暂时无法删除");
                        return;
                    }
                } else {
                    packStore.clearPartial(pack);
                }
            } catch (IOException error) {
                showMessage(error.getMessage());
                return;
            }
            invalidLocalData = false;
            invalidDataIsInstalledCandidate = false;
            invalidDataCanRetryRegistry = false;
        }
        startDownload();
    }

    private void startDownload() {
        try {
            packDownloads.start(pack);
            lastRenderedProgress = -1;
            renderActiveDownload(packDownloads.query());
            scheduleDownloadPoll();
        } catch (IOException | RuntimeException error) {
            renderAvailable();
            showMessage(readableError(error));
        }
    }

    private void verifyCompletedDownload() {
        if (verificationRunning) {
            return;
        }
        verificationRunning = true;
        renderVerifying();
        ioExecutor.execute(() -> {
            try {
                PackVerifier.verifyAndActivate(
                        getApplicationContext(),
                        pack,
                        packStore
                );
                packDownloads.removeCompletedRecord();
                postToUi(() -> {
                    verificationRunning = false;
                    invalidLocalData = false;
                    invalidDataIsInstalledCandidate = false;
                    invalidDataCanRetryRegistry = false;
                    renderReady();
                });
            } catch (Exception | LinkageError error) {
                boolean installedCandidate = packStore.hasInstalledCandidate(pack);
                boolean canRetryRegistry = installedCandidate
                        && error instanceof OfflinePackStore.RegistryWriteException;
                postToUi(() -> {
                    verificationRunning = false;
                    invalidLocalData = true;
                    invalidDataIsInstalledCandidate = installedCandidate;
                    invalidDataCanRetryRegistry = canRetryRegistry;
                    renderInvalidLocalData();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void verifyExistingCandidate() {
        if (verificationRunning) {
            return;
        }
        verificationRunning = true;
        renderVerifying();
        ioExecutor.execute(() -> {
            try {
                PackVerifier.verifyInstalled(
                        getApplicationContext(),
                        pack,
                        packStore
                );
                postToUi(() -> {
                    verificationRunning = false;
                    invalidLocalData = false;
                    invalidDataIsInstalledCandidate = false;
                    invalidDataCanRetryRegistry = false;
                    packDownloads.removeCompletedRecord();
                    renderReady();
                });
            } catch (Exception | LinkageError error) {
                boolean canRetryRegistry = error
                        instanceof OfflinePackStore.RegistryWriteException;
                postToUi(() -> {
                    verificationRunning = false;
                    invalidLocalData = true;
                    invalidDataIsInstalledCandidate = true;
                    invalidDataCanRetryRegistry = canRetryRegistry;
                    renderInvalidLocalData();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void renderAvailable() {
        storageStatus.setText("尚未下载");
        downloadDetail.setText(getString(R.string.download_network_detail, pack.humanSize()));
        downloadProgress.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText(R.string.download_pack);
        openSearchButton.setVisibility(View.GONE);
        removePackButton.setVisibility(View.GONE);
    }

    private void renderActiveDownload(DownloadSnapshot snapshot) {
        String state;
        if (snapshot.state == DownloadSnapshot.State.PAUSED) {
            state = pausedReason(snapshot.reason);
        } else if (snapshot.state == DownloadSnapshot.State.PENDING) {
            state = "等待系统开始下载";
        } else {
            state = "正在下载";
        }
        int percent = snapshot.percent();
        storageStatus.setText(state);
        downloadProgress.setVisibility(View.VISIBLE);
        if (percent != lastRenderedProgress) {
            lastRenderedProgress = percent;
            downloadProgress.setProgress(percent);
            String total = snapshot.totalBytes > 0
                    ? OfflinePack.formatBytes(snapshot.totalBytes)
                    : pack.humanSize();
            downloadDetail.setText(getString(
                    R.string.download_detail_format,
                    percent,
                    OfflinePack.formatBytes(snapshot.downloadedBytes),
                    total
            ));
            downloadProgress.setContentDescription(
                    getString(R.string.download_progress_format, percent)
            );
        }
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText(R.string.cancel_download);
        openSearchButton.setVisibility(View.GONE);
        removePackButton.setVisibility(View.GONE);
    }

    private void renderVerifying() {
        storageStatus.setText("正在校验离线包");
        downloadDetail.setText(R.string.verifying_detail);
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(100);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(false);
        downloadButton.setText("正在校验");
        openSearchButton.setVisibility(View.GONE);
        removePackButton.setVisibility(View.GONE);
    }

    private void renderReady() {
        storageStatus.setText("可以离线阅读");
        downloadDetail.setText(getString(R.string.ready_detail, pack.version, pack.humanSize()));
        downloadProgress.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        openSearchButton.setVisibility(View.VISIBLE);
        openSearchButton.setEnabled(true);
        removePackButton.setVisibility(View.VISIBLE);
        removePackButton.setEnabled(true);
    }

    private void renderDownloadFailure(int reason) {
        storageStatus.setText("下载失败");
        downloadDetail.setText(downloadFailureReason(reason));
        downloadProgress.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText(R.string.retry_download);
        openSearchButton.setVisibility(View.GONE);
        removePackButton.setVisibility(View.GONE);
    }

    private void renderInvalidLocalData() {
        storageStatus.setText("离线包校验失败");
        if (invalidDataCanRetryRegistry) {
            downloadDetail.setText("文件已经校验通过，但状态保存失败；可直接重试，无需重新下载。");
        } else {
            downloadDetail.setText("保留了问题文件；点击下方按钮后会删除并重新下载。");
        }
        downloadProgress.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText(invalidDataCanRetryRegistry ? "重试保存状态" : "删除并重新下载");
        openSearchButton.setVisibility(View.GONE);
        removePackButton.setVisibility(View.GONE);
    }

    private void openSearch() {
        if (!packStore.isInstalled(pack)) {
            showMessage("请先完成离线包下载和校验");
            return;
        }
        if (archive != null) {
            showSearch();
            return;
        }

        openSearchButton.setEnabled(false);
        removePackButton.setEnabled(false);
        storageStatus.setText("正在打开离线包");
        downloadDetail.setText("首次打开需要加载本地搜索索引…");
        int generation = archiveGeneration.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                File file = packStore.installedFile(pack);
                KiwixArchive opened = KiwixArchive.open(getApplicationContext(), file);
                if (generation != archiveGeneration.get()) {
                    opened.close();
                    return;
                }
                postToUi(() -> {
                    if (generation != archiveGeneration.get()) {
                        opened.close();
                        return;
                    }
                    archive = opened;
                    attachArchiveToWebView();
                    openSearchButton.setEnabled(true);
                    removePackButton.setEnabled(true);
                    showSearch();
                });
            } catch (Exception | LinkageError error) {
                postToUi(() -> {
                    openSearchButton.setEnabled(true);
                    removePackButton.setEnabled(true);
                    renderReady();
                    showMessage("无法打开离线包：" + readableError(error));
                });
            }
        });
    }

    private void attachArchiveToWebView() {
        articleWebView.setWebViewClient(new ZimWebViewClient(
                archive,
                new ZimWebViewClient.Listener() {
                    @Override
                    public void onExternalLinkBlocked() {
                        showMessage(getString(R.string.external_link_blocked));
                    }

                    @Override
                    public void onPageStarted() {
                        // Deliberately do not repaint the toolbar for transient loading state.
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
        if (archive == null) {
            showMessage("离线包尚未打开");
            return;
        }
        hideKeyboard();
        int generation = searchGeneration.incrementAndGet();
        searchButton.setEnabled(false);
        searchStatus.setText(R.string.searching);
        ioExecutor.execute(() -> {
            List<SearchResult> results;
            try {
                results = archive.search(term, SEARCH_LIMIT);
            } catch (RuntimeException error) {
                postToUi(() -> {
                    if (generation == searchGeneration.get()) {
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
                if (generation != searchGeneration.get()) {
                    return;
                }
                searchButton.setEnabled(true);
                resultAdapter.replace(results);
                searchResults.setSelection(0);
                if (results.isEmpty()) {
                    searchStatus.setText(R.string.search_no_results);
                } else {
                    searchStatus.setText(getResources().getQuantityString(
                            R.plurals.search_result_count,
                            results.size(),
                            results.size()
                    ));
                }
            });
        });
    }

    private void openArticle(SearchResult result) {
        if (archive == null || result.path.isEmpty()) {
            return;
        }
        hideKeyboard();
        currentScreen = Screen.READER;
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        libraryButton.setVisibility(View.VISIBLE);
        toolbarTitle.setText(result.title);
        clearHistoryOnPageFinish = true;
        articleWebView.loadUrl(archive.contentUrl(result.path));
    }

    private void showLibrary() {
        currentScreen = Screen.LIBRARY;
        libraryScreen.setVisibility(View.VISIBLE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.GONE);
        libraryButton.setVisibility(View.GONE);
        toolbarTitle.setText(R.string.library_title);
        hideKeyboard();
        refreshLibraryState();
    }

    private void showSearch() {
        currentScreen = Screen.SEARCH;
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.VISIBLE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.VISIBLE);
        libraryButton.setVisibility(View.GONE);
        toolbarTitle.setText(R.string.search_title);
    }

    private void handleBack() {
        if (currentScreen == Screen.READER) {
            if (articleWebView.canGoBack()) {
                articleWebView.goBack();
            } else {
                showSearch();
            }
        } else if (currentScreen == Screen.SEARCH) {
            showLibrary();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (currentScreen == Screen.READER && keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            pageBy(-1);
            return true;
        }
        if (currentScreen == Screen.READER && keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            pageBy(1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void pageBy(int direction) {
        int overlap = Math.round(48 * getResources().getDisplayMetrics().density);
        int distance = Math.max(1, articleWebView.getHeight() - overlap);
        articleWebView.scrollBy(0, direction * distance);
    }

    private void adjustTextZoom(int delta) {
        textZoom = Math.max(MIN_TEXT_ZOOM, Math.min(MAX_TEXT_ZOOM, textZoom + delta));
        articleWebView.getSettings().setTextZoom(textZoom);
        getPreferences(MODE_PRIVATE).edit().putInt("reader_text_zoom", textZoom).apply();
        showMessage("正文缩放 " + textZoom + "%");
    }

    private void confirmRemovePack() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_remove_title)
                .setMessage(R.string.confirm_remove_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (ignored, which) -> removePack())
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(0);
        }
        dialog.show();
    }

    private void removePack() {
        showLibrary();
        storageStatus.setText("正在删除离线包");
        openSearchButton.setEnabled(false);
        removePackButton.setEnabled(false);
        archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        packDownloads.cancelTrackedDownload();
        articleWebView.stopLoading();
        articleWebView.setWebViewClient(new WebViewClient());
        articleWebView.loadUrl("about:blank");
        resultAdapter.replace(null);

        KiwixArchive toClose = archive;
        archive = null;
        ioExecutor.execute(() -> {
            if (toClose != null) {
                toClose.close();
            }
            try {
                boolean removed = packStore.deleteInstalled(pack);
                postToUi(() -> {
                    openSearchButton.setEnabled(true);
                    removePackButton.setEnabled(true);
                    if (removed) {
                        renderAvailable();
                    } else {
                        renderReady();
                        showMessage("文件仍在使用，暂时无法删除");
                    }
                });
            } catch (IOException error) {
                postToUi(() -> {
                    openSearchButton.setEnabled(true);
                    removePackButton.setEnabled(true);
                    renderReady();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void loadInstalledVersionName() {
        try {
            installedVersionName = updateVerifier.currentVersionName();
        } catch (UpdateException ignored) {
            installedVersionName = BuildConfig.VERSION_NAME;
        }
    }

    private void handleUpdateButton() {
        if (BuildConfig.DEBUG || activeUpdateThread != null) {
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
        updateButton.setEnabled(activeUpdateThread == null
                && updateState != UpdateState.CHECKING
                && updateState != UpdateState.DOWNLOADING);
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
                        if (UpdatePolicy.isNewer(
                                result.release.versionName(),
                                result.currentVersion
                        )) {
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
        if (activeUpdateThread != null) {
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
                    File downloadedFile = null;
                    try {
                        downloadedFile = client.download(
                                release,
                                new File(getCacheDir(), UPDATE_CACHE_DIRECTORY)
                        );
                        return updateVerifier.verify(downloadedFile, release);
                    } catch (Exception error) {
                        if (downloadedFile != null) {
                            //noinspection ResultOfMethodCallIgnored
                            downloadedFile.delete();
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
        updateGeneration += 1;
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
        if (message == null || message.isEmpty()) {
            message = "操作失败";
        }
        messageBar.setText(message);
        messageBar.setVisibility(View.VISIBLE);
    }

    private static String readableError(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static String pausedReason(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return "等待网络连接";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return "等待 Wi-Fi";
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return "等待系统重试";
            default:
                return "下载已暂停";
        }
    }

    private static String downloadFailureReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "存储空间不足";
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "服务器无法继续此下载，请重试";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "网络传输中断，请重试";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "下载服务器返回了无法处理的状态";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "目标文件已经存在";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "存储设备不可用";
            default:
                return "错误代码 " + reason + "，请检查网络后重试";
        }
    }

    @Override
    protected void onDestroy() {
        cancelActiveUpdateTask(false);
        destroyed = true;
        resumed = false;
        mainHandler.removeCallbacksAndMessages(null);
        searchGeneration.incrementAndGet();
        archiveGeneration.incrementAndGet();

        articleWebView.stopLoading();
        articleWebView.setWebViewClient(new WebViewClient());
        articleWebView.destroy();

        KiwixArchive toClose = archive;
        archive = null;
        if (toClose != null) {
            ioExecutor.execute(toClose::close);
        }
        ioExecutor.shutdown();
        super.onDestroy();
    }
}
