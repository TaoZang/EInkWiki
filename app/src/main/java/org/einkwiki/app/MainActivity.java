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
import android.os.SystemClock;
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
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.einkwiki.app.data.OfflinePack;
import org.einkwiki.app.data.InstalledPackSnapshotStore;
import org.einkwiki.app.data.KiwixCatalogClient;
import org.einkwiki.app.data.OfflinePackCatalogCache;
import org.einkwiki.app.data.OfflinePackSelectionStore;
import org.einkwiki.app.data.OfflinePackStore;
import org.einkwiki.app.download.DownloadSnapshot;
import org.einkwiki.app.download.DownloadSpeedTracker;
import org.einkwiki.app.download.PackDownloadManager;
import org.einkwiki.app.library.OfflinePackAdapter;
import org.einkwiki.app.library.PackRowModel;
import org.einkwiki.app.reader.KiwixArchive;
import org.einkwiki.app.reader.PackVerifier;
import org.einkwiki.app.reader.ReaderPageKeyMapper;
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
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-activity, animation-free offline Wikipedia reader for e-ink Android devices. */
public final class MainActivity extends Activity {
    private enum Screen {
        HOME,
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "einkwiki-reader");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private final AtomicInteger archiveGeneration = new AtomicInteger();
    private final DownloadSpeedTracker downloadSpeedTracker = new DownloadSpeedTracker();

    private OfflinePackStore packStore;
    private PackDownloadManager packDownloads;
    private KiwixCatalogClient catalogClient;
    private OfflinePackCatalogCache catalogCache;
    private InstalledPackSnapshotStore installedPackSnapshots;
    private OfflinePackSelectionStore packSelection;
    private OfflinePackAdapter packAdapter;
    private final List<OfflinePack> catalogPacks = new ArrayList<>();
    private final Map<String, OfflinePack> packsById = new HashMap<>();
    private final Map<String, InvalidPackState> invalidPacks = new HashMap<>();
    private final Map<String, String> packFailures = new HashMap<>();
    private OfflinePack selectedPack;
    private KiwixArchive archive;
    private String archivePackId = "";
    private SearchResultAdapter resultAdapter;
    private UpdatePackageVerifier updateVerifier;
    private SystemUpdateInstaller updateInstaller;

    private View homeScreen;
    private View libraryScreen;
    private View searchScreen;
    private View readerScreen;
    private Button backButton;
    private Button libraryButton;
    private TextView toolbarTitle;
    private TextView messageBar;
    private TextView catalogStatus;
    private Button refreshCatalogButton;
    private ListView offlinePackList;
    private EditText homeSearchInput;
    private EditText searchInput;
    private Button searchButton;
    private TextView searchStatus;
    private ListView searchResults;
    private WebView articleWebView;
    private TextView currentVersionView;
    private TextView updateStatus;
    private Button updateButton;

    private Screen currentScreen = Screen.HOME;
    private Screen searchReturnScreen = Screen.HOME;
    private boolean resumed;
    private boolean destroyed;
    private boolean catalogRefreshAttempted;
    private boolean catalogRefreshRunning;
    private boolean packReconcilePending = true;
    private String resolvingPackId = "";
    private String verifyingPackId = "";
    private String openingPackId = "";
    private String deletingPackId = "";
    private boolean clearHistoryOnPageFinish;
    private int textZoom;
    private int navigationGeneration;
    private int updateGeneration;
    private UpdateState updateState = UpdateState.IDLE;
    private String installedVersionName = BuildConfig.VERSION_NAME;
    private UpdateRelease availableUpdate;
    private VerifiedUpdate verifiedUpdate;
    private UpdateClient activeUpdateClient;
    private Thread activeUpdateThread;
    private boolean waitingForInstallPermission;

    private static final class InvalidPackState {
        final boolean installedCandidate;
        final boolean canRetryRegistry;
        final String message;

        InvalidPackState(boolean installedCandidate, boolean canRetryRegistry, String message) {
            this.installedCandidate = installedCandidate;
            this.canRetryRegistry = canRetryRegistry;
            this.message = message;
        }
    }

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
        catalogClient = new KiwixCatalogClient();
        catalogCache = new OfflinePackCatalogCache(this);
        installedPackSnapshots = new InstalledPackSnapshotStore(this);
        packSelection = new OfflinePackSelectionStore(this);
        try {
            installedPackSnapshots.migrateDevelopmentPack(packStore);
        } catch (IOException ignored) {
            // The verified file is still recoverable; the registry is retried below.
        }
        loadLocalPackCatalog();
        UpdateCacheCleaner.clearAbandoned(this);
        updateVerifier = new UpdatePackageVerifier(this);
        updateInstaller = new SystemUpdateInstaller(this);
        resultAdapter = new SearchResultAdapter(this);
        searchResults.setAdapter(resultAdapter);
        packAdapter = new OfflinePackAdapter(this, this::handlePackAction);
        offlinePackList.setAdapter(packAdapter);
        textZoom = getPreferences(MODE_PRIVATE).getInt("reader_text_zoom", DEFAULT_TEXT_ZOOM);

        configureReader();
        bindActions();
        loadInstalledVersionName();
        renderUpdateSection();
        showHome();
        beginPackReconciliation();
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
        homeScreen = findViewById(R.id.home_screen);
        libraryScreen = findViewById(R.id.library_screen);
        searchScreen = findViewById(R.id.search_screen);
        readerScreen = findViewById(R.id.reader_screen);
        backButton = findViewById(R.id.back_button);
        libraryButton = findViewById(R.id.library_button);
        toolbarTitle = findViewById(R.id.toolbar_title);
        messageBar = findViewById(R.id.message_bar);
        offlinePackList = findViewById(R.id.offline_pack_list);
        offlinePackList.setItemsCanFocus(true);
        View libraryHeader = getLayoutInflater().inflate(
                R.layout.library_header,
                offlinePackList,
                false
        );
        View libraryFooter = getLayoutInflater().inflate(
                R.layout.library_footer,
                offlinePackList,
                false
        );
        offlinePackList.addHeaderView(libraryHeader, null, false);
        offlinePackList.addFooterView(libraryFooter, null, false);
        catalogStatus = libraryHeader.findViewById(R.id.catalog_status);
        refreshCatalogButton = libraryHeader.findViewById(R.id.refresh_catalog_button);
        homeSearchInput = findViewById(R.id.home_search_input);
        searchInput = findViewById(R.id.search_input);
        searchButton = findViewById(R.id.search_button);
        searchStatus = findViewById(R.id.search_status);
        searchResults = findViewById(R.id.search_results);
        articleWebView = findViewById(R.id.article_webview);
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
        refreshCatalogButton.setOnClickListener(view -> refreshCatalog());
        updateButton.setOnClickListener(view -> handleUpdateButton());
        homeSearchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                performHomeSearch();
                return true;
            }
            return false;
        });
        searchButton.setOnClickListener(view -> performSearch());
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
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
        if (!packReconcilePending) {
            refreshLibraryState();
            scheduleDownloadPoll();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(downloadPoll);
        downloadSpeedTracker.reset();
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
        OfflinePack tracked = packDownloads.trackedPack();
        if (tracked == null) {
            tracked = findPack(packDownloads.trackedPackId());
        }
        DownloadSnapshot snapshot = packDownloads.query();
        if (tracked != null && packStore.isInstalled(tracked)) {
            try {
                installedPackSnapshots.save(tracked);
                packDownloads.removeCompletedRecord(tracked);
                invalidPacks.remove(tracked.artifactId());
            } catch (IOException error) {
                invalidPacks.put(tracked.artifactId(), new InvalidPackState(
                        true,
                        true,
                        readableError(error)
                ));
            }
            snapshot = DownloadSnapshot.none();
        } else if (tracked != null
                && snapshot.state == DownloadSnapshot.State.SUCCESSFUL
                && verifyingPackId.isEmpty()
                && !invalidPacks.containsKey(tracked.artifactId())) {
            verifyCompletedDownload(tracked);
        } else if (snapshot.state == DownloadSnapshot.State.NONE
                && packDownloads.trackedId() != -1L) {
            packDownloads.clearTracking();
        }

        if (verifyingPackId.isEmpty()
                && !snapshot.isActive()
                && snapshot.state != DownloadSnapshot.State.SUCCESSFUL) {
            for (OfflinePack candidate : catalogPacks) {
                if (packStore.hasInstalledCandidate(candidate)
                        && !packStore.isInstalled(candidate)
                        && !invalidPacks.containsKey(candidate.artifactId())) {
                    verifyExistingCandidate(candidate);
                    break;
                }
            }
        }
        restoreSelectedPack();
        renderPackRows();
    }

    private void handlePackAction(PackRowModel row, PackRowModel.Action action) {
        if (packReconcilePending) {
            showMessage("正在恢复离线书库状态，请稍候");
            return;
        }
        OfflinePack target = findPack(row.packKey);
        if (target == null) {
            showMessage("这个离线包已经不在目录中，请更新目录");
            return;
        }
        switch (action) {
            case DOWNLOAD:
            case RETRY:
            case UPDATE:
                requestPackDownload(target);
                break;
            case REDOWNLOAD:
                repairAndDownload(target);
                break;
            case RETRY_REGISTRY:
                repairAndDownload(target);
                break;
            case CANCEL:
                cancelPackDownload(target);
                break;
            case SET_CURRENT:
                selectPack(target, true);
                break;
            case OPEN_SEARCH:
                openSearch(target, Screen.LIBRARY, false);
                break;
            case DELETE:
                confirmRemovePack(target);
                break;
            default:
                break;
        }
    }

    private void requestPackDownload(OfflinePack target) {
        if (!resolvingPackId.isEmpty() || !verifyingPackId.isEmpty()) {
            showMessage("另一个离线包正在准备或校验，请稍候");
            return;
        }
        long trackedId = packDownloads.trackedId();
        if (trackedId != -1L && !packDownloads.isTracked(target)) {
            OfflinePack active = packDownloads.trackedPack();
            if (active == null) {
                active = findPack(packDownloads.trackedPackId());
            }
            showMessage(active == null
                    ? "另一个离线包正在下载，请先取消"
                    : "正在下载《" + displayTitle(active) + "》，请先取消该下载");
            return;
        }
        if (packDownloads.isTracked(target)) {
            DownloadSnapshot snapshot = packDownloads.query(target);
            if (snapshot.isActive() || snapshot.state == DownloadSnapshot.State.SUCCESSFUL) {
                return;
            }
            packDownloads.removeCompletedRecord(target);
        }
        invalidPacks.remove(target.artifactId());
        packFailures.remove(target.artifactId());
        if (target.hasDownloadMetadata()) {
            startResolvedDownload(target);
            return;
        }

        resolvingPackId = target.artifactId();
        renderPackRows();
        scrollPackIntoView(target.artifactId());
        List<OfflinePack> catalogSnapshot = new ArrayList<>(catalogPacks);
        PackTaskCoordinator.execute(() -> {
            try {
                OfflinePack resolved = catalogClient.resolveDownloadMetadata(target);
                catalogCache.upsert(
                        catalogSnapshot,
                        resolved,
                        System.currentTimeMillis()
                );
                postToUi(() -> {
                    if (!target.artifactId().equals(resolvingPackId)) {
                        return;
                    }
                    resolvingPackId = "";
                    applyPackCatalog(replacePack(catalogPacks, resolved));
                    startResolvedDownload(resolved);
                });
            } catch (Exception error) {
                postToUi(() -> {
                    if (target.artifactId().equals(resolvingPackId)) {
                        resolvingPackId = "";
                        packFailures.put(target.artifactId(), readableError(error));
                        renderPackRows();
                        showMessage("无法准备下载：" + readableError(error));
                    }
                });
            }
        });
    }

    private void startResolvedDownload(OfflinePack target) {
        try {
            packDownloads.start(target);
            packFailures.remove(target.artifactId());
            renderPackRows();
            scrollPackIntoView(target.artifactId());
            scheduleDownloadPoll();
        } catch (IOException | RuntimeException error) {
            packFailures.put(target.artifactId(), readableError(error));
            renderPackRows();
            showMessage(readableError(error));
        }
    }

    private void cancelPackDownload(OfflinePack target) {
        if (!packDownloads.cancel(target)) {
            return;
        }
        try {
            packStore.clearPartial(target);
        } catch (IOException ignored) {
            // DownloadManager may still be finishing exact-file cleanup.
        }
        invalidPacks.remove(target.artifactId());
        packFailures.remove(target.artifactId());
        renderPackRows();
    }

    private void repairAndDownload(OfflinePack target) {
        InvalidPackState invalid = invalidPacks.get(target.artifactId());
        if (invalid == null) {
            requestPackDownload(target);
            return;
        }
        if (invalid.installedCandidate && invalid.canRetryRegistry) {
            invalidPacks.remove(target.artifactId());
            verifyExistingCandidate(target);
            return;
        }
        deletingPackId = target.artifactId();
        KiwixArchive archiveToClose = detachArchiveForMutation(target);
        renderPackRows();
        PackTaskCoordinator.execute(() -> {
            try {
                if (archiveToClose != null) {
                    archiveToClose.close();
                }
                if (invalid.installedCandidate) {
                    if (!packStore.deleteInstalled(target)) {
                        throw new IOException("文件仍在使用，暂时无法删除");
                    }
                } else {
                    packDownloads.removeCompletedRecord(target);
                    packStore.clearPartial(target);
                }
                postToUi(() -> {
                    deletingPackId = "";
                    invalidPacks.remove(target.artifactId());
                    requestPackDownload(target);
                });
            } catch (IOException error) {
                postToUi(() -> {
                    deletingPackId = "";
                    renderPackRows();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void verifyCompletedDownload(OfflinePack target) {
        if (!verifyingPackId.isEmpty()) {
            return;
        }
        verifyingPackId = target.artifactId();
        renderPackRows();
        PackTaskCoordinator.execute(() -> {
            try {
                PackVerifier.verifyAndActivate(
                        getApplicationContext(),
                        target,
                        packStore
                );
                installedPackSnapshots.save(target);
                packDownloads.removeCompletedRecord(target);
                postToUi(() -> {
                    verifyingPackId = "";
                    invalidPacks.remove(target.artifactId());
                    packFailures.remove(target.artifactId());
                    if (selectedPack == null || !packStore.isInstalled(selectedPack)) {
                        selectPack(target, false);
                    } else {
                        refreshLibraryState();
                    }
                });
            } catch (Exception | LinkageError error) {
                boolean installedCandidate = packStore.hasInstalledCandidate(target);
                boolean canRetryRegistry = installedCandidate
                        && (error instanceof OfflinePackStore.RegistryWriteException
                        || packStore.isInstalled(target));
                postToUi(() -> {
                    verifyingPackId = "";
                    invalidPacks.put(target.artifactId(), new InvalidPackState(
                            installedCandidate,
                            canRetryRegistry,
                            readableError(error)
                    ));
                    renderPackRows();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void verifyExistingCandidate(OfflinePack target) {
        if (!verifyingPackId.isEmpty()) {
            return;
        }
        verifyingPackId = target.artifactId();
        renderPackRows();
        PackTaskCoordinator.execute(() -> {
            try {
                PackVerifier.verifyInstalled(
                        getApplicationContext(),
                        target,
                        packStore
                );
                installedPackSnapshots.save(target);
                postToUi(() -> {
                    verifyingPackId = "";
                    invalidPacks.remove(target.artifactId());
                    packDownloads.removeCompletedRecord(target);
                    if (selectedPack == null || !packStore.isInstalled(selectedPack)) {
                        selectPack(target, false);
                    } else {
                        refreshLibraryState();
                    }
                });
            } catch (Exception | LinkageError error) {
                boolean canRetryRegistry = error
                        instanceof OfflinePackStore.RegistryWriteException
                        || packStore.isInstalled(target);
                postToUi(() -> {
                    verifyingPackId = "";
                    invalidPacks.put(target.artifactId(), new InvalidPackState(
                            true,
                            canRetryRegistry,
                            readableError(error)
                    ));
                    renderPackRows();
                    showMessage(readableError(error));
                });
            }
        });
    }

    private void renderPackRows() {
        if (packAdapter == null) {
            return;
        }
        DownloadSnapshot activeSnapshot = packDownloads.query();
        String activeId = packDownloads.trackedPackId();
        long activeBytesPerSecond = downloadSpeedTracker.update(
                activeId,
                activeSnapshot,
                SystemClock.elapsedRealtime()
        );
        List<OfflinePack> sorted = new ArrayList<>(catalogPacks);
        Map<String, Boolean> installedState = new HashMap<>();
        for (OfflinePack candidate : sorted) {
            installedState.put(candidate.artifactId(), packStore.isInstalled(candidate));
        }
        sorted.sort(packComparator(activeId, installedState));
        List<PackRowModel> rows = new ArrayList<>(sorted.size());
        for (OfflinePack candidate : sorted) {
            rows.add(rowForPack(
                    candidate,
                    Boolean.TRUE.equals(installedState.get(candidate.artifactId())),
                    activeId,
                    activeSnapshot,
                    activeBytesPerSecond
            ));
        }
        packAdapter.submitRows(rows, offlinePackList);
        updateKeepScreenOn();
    }

    private void scrollPackIntoView(String artifactId) {
        List<PackRowModel> rows = packAdapter.rows();
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).packKey.equals(artifactId)) {
                int position = offlinePackList.getHeaderViewsCount() + index;
                offlinePackList.setSelectionFromTop(position, 0);
                return;
            }
        }
    }

    private PackRowModel rowForPack(
            OfflinePack candidate,
            boolean installed,
            String activeId,
            DownloadSnapshot activeSnapshot,
            long activeBytesPerSecond
    ) {
        String id = candidate.artifactId();
        boolean current = installed
                && selectedPack != null
                && id.equals(selectedPack.artifactId());
        String badge = current ? "当前" : installed ? "已下载" : recommendedRank(candidate) < 100
                ? "推荐" : "";
        String metadata = candidate.version + " · " + candidate.humanSize()
                + " · " + flavourLabel(candidate.flavour);
        String detail = candidate.articleCount >= 0L
                ? NumberFormat.getIntegerInstance(Locale.CHINA).format(candidate.articleCount)
                + " 篇条目"
                : candidate.description;
        PackRowModel.State state;
        String status;
        int progress = PackRowModel.NO_PROGRESS;

        if (id.equals(deletingPackId)) {
            state = PackRowModel.State.DELETING;
            status = "正在删除离线包";
        } else if (id.equals(resolvingPackId)) {
            state = PackRowModel.State.PREPARING;
            status = "正在读取下载校验信息";
        } else if (id.equals(openingPackId)) {
            state = PackRowModel.State.PREPARING;
            status = "正在打开本地搜索索引";
        } else if (id.equals(verifyingPackId)
                || (id.equals(activeId)
                && activeSnapshot.state == DownloadSnapshot.State.SUCCESSFUL)) {
            state = PackRowModel.State.VERIFYING;
            status = "正在校验大小、SHA-256 和 ZIM 结构";
        } else if (invalidPacks.containsKey(id)) {
            InvalidPackState invalid = invalidPacks.get(id);
            state = invalid.canRetryRegistry
                    ? PackRowModel.State.REGISTRY_FAILED
                    : PackRowModel.State.VERIFICATION_FAILED;
            status = invalid.canRetryRegistry ? "校验通过，状态保存失败" : "离线包校验失败";
            detail = invalid.message;
        } else if (installed) {
            state = current ? PackRowModel.State.CURRENT : PackRowModel.State.INSTALLED;
            status = current ? "当前搜索库 · 已校验" : "已下载 · 已校验";
        } else if (id.equals(activeId)) {
            switch (activeSnapshot.state) {
                case PENDING:
                    state = PackRowModel.State.PENDING;
                    status = "等待系统开始下载";
                    progress = activeSnapshot.percent();
                    break;
                case RUNNING:
                    state = PackRowModel.State.DOWNLOADING;
                    status = activeBytesPerSecond
                            == DownloadSpeedTracker.UNKNOWN_BYTES_PER_SECOND
                            ? "正在下载 · 正在计算速度"
                            : "正在下载 · "
                            + OfflinePack.formatBytes(activeBytesPerSecond)
                            + "/s";
                    progress = activeSnapshot.percent();
                    break;
                case PAUSED:
                    state = PackRowModel.State.PAUSED;
                    status = pausedReason(activeSnapshot.reason);
                    progress = activeSnapshot.percent();
                    break;
                case FAILED:
                    state = PackRowModel.State.DOWNLOAD_FAILED;
                    status = "下载失败";
                    detail = downloadFailureReason(activeSnapshot.reason);
                    break;
                default:
                    state = PackRowModel.State.AVAILABLE;
                    status = "尚未下载";
                    break;
            }
            if (activeSnapshot.state == DownloadSnapshot.State.PENDING
                    || activeSnapshot.state == DownloadSnapshot.State.RUNNING
                    || activeSnapshot.state == DownloadSnapshot.State.PAUSED) {
                String total = activeSnapshot.totalBytes > 0
                        ? OfflinePack.formatBytes(activeSnapshot.totalBytes)
                        : candidate.humanSize();
                detail = activeSnapshot.downloadedBytes > 0
                        ? progress + "% · "
                        + OfflinePack.formatBytes(activeSnapshot.downloadedBytes)
                        + " / " + total
                        : progress + "% · " + total;
            }
        } else if (packFailures.containsKey(id)) {
            state = PackRowModel.State.DOWNLOAD_FAILED;
            status = "无法开始下载";
            detail = packFailures.get(id);
        } else if ((!activeId.isEmpty() && packDownloads.trackedId() != -1L)
                || !resolvingPackId.isEmpty()
                || !verifyingPackId.isEmpty()) {
            state = PackRowModel.State.DOWNLOAD_BLOCKED;
            status = "另一个离线包正在下载或校验";
        } else {
            state = PackRowModel.State.AVAILABLE;
            status = "尚未下载";
        }
        return new PackRowModel(
                id,
                displayTitle(candidate),
                metadata,
                badge,
                status,
                detail,
                state,
                progress
        );
    }

    private void loadLocalPackCatalog() {
        OfflinePackCatalogCache.Snapshot cached = catalogCache.loadSnapshot();
        List<OfflinePack> initial = new ArrayList<>(cached.packs);
        if (initial.isEmpty()) {
            initial.add(OfflinePack.DEVELOPMENT);
            catalogStatus.setText(R.string.catalog_builtin_status);
        } else {
            catalogStatus.setText(R.string.catalog_cache_status);
        }
        for (OfflinePack installed : installedPackSnapshots.loadAll()) {
            initial = replacePack(initial, installed);
        }
        applyPackCatalog(initial);
    }

    private void applyPackCatalog(List<OfflinePack> packs) {
        LinkedHashMap<String, OfflinePack> merged = new LinkedHashMap<>();
        for (OfflinePack candidate : packs) {
            OfflinePack previous = packsById.get(candidate.artifactId());
            merged.put(candidate.artifactId(), candidate.hasDownloadMetadata()
                    ? candidate
                    : previous != null && previous.hasDownloadMetadata() ? previous : candidate);
        }
        for (OfflinePack installed : installedPackSnapshots.loadAll()) {
            merged.put(installed.artifactId(), installed);
        }
        OfflinePack tracked = packDownloads.trackedPack();
        if (tracked != null) {
            merged.put(tracked.artifactId(), tracked);
        }
        catalogPacks.clear();
        catalogPacks.addAll(merged.values());
        packsById.clear();
        for (OfflinePack candidate : catalogPacks) {
            packsById.put(candidate.artifactId(), candidate);
        }
        restoreSelectedPack();
        renderPackRows();
    }

    private static List<OfflinePack> replacePack(
            List<OfflinePack> source,
            OfflinePack replacement
    ) {
        List<OfflinePack> result = new ArrayList<>(source.size() + 1);
        boolean replaced = false;
        for (OfflinePack candidate : source) {
            if (candidate.artifactId().equals(replacement.artifactId())) {
                if (!replaced) {
                    result.add(replacement);
                    replaced = true;
                }
            } else {
                result.add(candidate);
            }
        }
        if (!replaced) {
            result.add(replacement);
        }
        return result;
    }

    private OfflinePack findPack(String artifactId) {
        return artifactId == null ? null : packsById.get(artifactId);
    }

    private void restoreSelectedPack() {
        String selectedId = packSelection.selectedArtifactId();
        OfflinePack stored = findPack(selectedId);
        if (stored != null && packStore.isInstalled(stored)) {
            selectedPack = stored;
            return;
        }
        selectedPack = null;
        List<OfflinePack> installed = new ArrayList<>();
        for (OfflinePack candidate : catalogPacks) {
            if (packStore.isInstalled(candidate)) {
                installed.add(candidate);
                if (installedPackSnapshots.find(candidate.artifactId()) == null) {
                    try {
                        installedPackSnapshots.save(candidate);
                    } catch (IOException ignored) {
                        // The verified file remains usable and is retried on the next refresh.
                    }
                }
            }
        }
        installed.sort(Comparator.comparing(OfflinePack::artifactId));
        if (!installed.isEmpty()) {
            selectedPack = installed.get(0);
            try {
                packSelection.select(selectedPack);
            } catch (IOException ignored) {
                // Search remains usable for this process; persistence can be retried by the user.
            }
        } else if (!selectedId.isEmpty()) {
            try {
                packSelection.clear();
            } catch (IOException ignored) {
                // An invalid selection never makes an unverified file usable.
            }
        }
    }

    private void selectPack(OfflinePack target, boolean announce) {
        if (!packStore.isInstalled(target)) {
            showMessage("这个离线包尚未完成校验");
            return;
        }
        String previous = selectedPack == null ? "" : selectedPack.artifactId();
        try {
            packSelection.select(target);
        } catch (IOException error) {
            showMessage(readableError(error));
            return;
        }
        selectedPack = target;
        if (!previous.equals(target.artifactId())) {
            invalidateArchiveSession();
        }
        renderPackRows();
        if (announce) {
            showMessage("已将《" + displayTitle(target) + "》设为当前搜索库");
        }
    }

    private Comparator<OfflinePack> packComparator(
            String activeId,
            Map<String, Boolean> installedState
    ) {
        return Comparator
                .comparingInt((OfflinePack candidate) -> packGroup(
                        candidate,
                        activeId,
                        Boolean.TRUE.equals(installedState.get(candidate.artifactId()))
                ))
                .thenComparingInt(MainActivity::recommendedRank)
                .thenComparingLong(candidate -> {
                    long bytes = candidate.hasDownloadMetadata()
                            ? candidate.expectedBytes : candidate.advertisedBytes;
                    return bytes < 0L ? Long.MAX_VALUE : bytes;
                })
                .thenComparing(MainActivity::displayTitle)
                .thenComparing(OfflinePack::artifactId);
    }

    private int packGroup(OfflinePack candidate, String activeId, boolean installed) {
        if (selectedPack != null
                && candidate.artifactId().equals(selectedPack.artifactId())
                && installed) {
            return 0;
        }
        if (installed) {
            return 1;
        }
        if (candidate.artifactId().equals(activeId)
                || candidate.artifactId().equals(resolvingPackId)
                || candidate.artifactId().equals(verifyingPackId)
                || invalidPacks.containsKey(candidate.artifactId())) {
            return 2;
        }
        return recommendedRank(candidate) < 100 ? 3 : 4;
    }

    private static int recommendedRank(OfflinePack candidate) {
        if ("wikipedia_zh_chemistry_nopic".equals(candidate.logicalId)) {
            return 0;
        }
        if ("wikipedia_zh_top_nopic".equals(candidate.logicalId)) {
            return 1;
        }
        if ("wikipedia_zh_all_nopic".equals(candidate.logicalId)) {
            return 2;
        }
        return 100;
    }

    private static String flavourLabel(String flavour) {
        switch (flavour) {
            case "mini":
                return "精简无图";
            case "nopic":
                return "完整正文无图";
            case "maxi":
                return "完整正文含图";
            default:
                return flavour;
        }
    }

    private static String displayTitle(OfflinePack candidate) {
        String id = candidate.logicalId;
        String scope;
        if (id.contains("_all_")) {
            scope = "全部条目";
        } else if (id.contains("_top_")) {
            scope = "热门条目";
        } else if (id.contains("_chemistry_")) {
            scope = "化学";
        } else if (id.contains("_physics_")) {
            scope = "物理";
        } else if (id.contains("_mathematics_")) {
            scope = "数学";
        } else if (id.contains("_movies_")) {
            scope = "电影";
        } else if (id.contains("_computer_")) {
            scope = "计算机";
        } else if (id.contains("_medicine_")) {
            scope = "医学";
        } else if (id.contains("_history_")) {
            scope = "历史";
        } else if (id.contains("_geography_")) {
            scope = "地理";
        } else if (id.contains("_molcell_")) {
            scope = "分子与细胞生物学";
        } else if (id.contains("_football_")) {
            scope = "足球";
        } else if (id.contains("_basketball_")) {
            scope = "篮球";
        } else {
            scope = candidate.title;
        }
        return "中文维基百科 · " + scope + " · " + flavourLabel(candidate.flavour);
    }

    private void refreshCatalog() {
        if (packReconcilePending) {
            showMessage("正在恢复离线书库状态，请稍候");
            return;
        }
        if (catalogRefreshRunning) {
            return;
        }
        catalogRefreshAttempted = true;
        catalogRefreshRunning = true;
        catalogStatus.setText(R.string.catalog_refreshing);
        refreshCatalogButton.setEnabled(false);
        List<OfflinePack> previous = new ArrayList<>(catalogPacks);
        Set<String> preservedIds = new HashSet<>(invalidPacks.keySet());
        preservedIds.add(packDownloads.trackedPackId());
        preservedIds.add(resolvingPackId);
        preservedIds.add(verifyingPackId);
        preservedIds.remove("");
        PackTaskCoordinator.execute(() -> {
            try {
                List<OfflinePack> remote = catalogClient.fetchChineseWikipedia();
                List<OfflinePack> latestPrevious = catalogCache.load();
                if (latestPrevious.isEmpty()) {
                    latestPrevious = previous;
                }
                Set<String> latestPreservedIds = new HashSet<>(preservedIds);
                OfflinePack activeDownload = packDownloads.trackedPack();
                if (activeDownload != null) {
                    latestPreservedIds.add(activeDownload.artifactId());
                    latestPrevious = replacePack(latestPrevious, activeDownload);
                }
                List<OfflinePack> merged = mergeRemoteCatalog(
                        remote,
                        latestPrevious,
                        latestPreservedIds
                );
                long savedAt = System.currentTimeMillis();
                catalogCache.save(merged, savedAt);
                postToUi(() -> {
                    catalogRefreshRunning = false;
                    refreshCatalogButton.setEnabled(true);
                    applyPackCatalog(merged);
                    catalogStatus.setText(getString(
                            R.string.catalog_ready_format,
                            remote.size()
                    ));
                });
            } catch (Exception error) {
                postToUi(() -> {
                    catalogRefreshRunning = false;
                    refreshCatalogButton.setEnabled(true);
                    catalogStatus.setText(R.string.catalog_refresh_failed);
                    showMessage("目录更新失败：" + readableError(error));
                });
            }
        });
    }

    private static List<OfflinePack> mergeRemoteCatalog(
            List<OfflinePack> remote,
            List<OfflinePack> previous,
            Set<String> preservedIds
    ) {
        Map<String, OfflinePack> previousById = new HashMap<>();
        for (OfflinePack candidate : previous) {
            previousById.put(candidate.artifactId(), candidate);
        }
        List<OfflinePack> merged = new ArrayList<>(remote.size());
        for (OfflinePack candidate : remote) {
            OfflinePack cached = previousById.get(candidate.artifactId());
            merged.add(cached != null && cached.hasDownloadMetadata() ? cached : candidate);
        }
        for (OfflinePack candidate : previous) {
            if (preservedIds.contains(candidate.artifactId())
                    && merged.stream().noneMatch(item -> item.artifactId()
                    .equals(candidate.artifactId()))) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private void performHomeSearch() {
        if (packReconcilePending) {
            showMessage("正在恢复离线书库状态，请稍候");
            return;
        }
        String term = homeSearchInput.getText().toString().trim();
        if (term.isEmpty()) {
            return;
        }
        restoreSelectedPack();
        if (selectedPack == null || !packStore.isInstalled(selectedPack)) {
            showMessage(getString(R.string.home_pack_required));
            return;
        }
        searchInput.setText(term);
        searchInput.setSelection(term.length());
        openSearch(selectedPack, Screen.HOME, true);
    }

    private void openSearch(
            OfflinePack target,
            Screen returnScreen,
            boolean searchImmediately
    ) {
        if (packReconcilePending) {
            showMessage("正在恢复离线书库状态，请稍候");
            return;
        }
        if (!packStore.isInstalled(target)) {
            showMessage("请先完成离线包下载和校验");
            return;
        }
        if (archive != null && target.artifactId().equals(archivePackId)) {
            searchReturnScreen = returnScreen;
            showSearch();
            if (searchImmediately) {
                performSearch();
            }
            return;
        }

        if (archive != null) {
            invalidateArchiveSession();
        }
        Screen requestScreen = currentScreen;
        int navigationAtRequest = navigationGeneration;
        homeSearchInput.setEnabled(false);
        hideKeyboard();
        showMessage(getString(R.string.opening_offline_pack));
        openingPackId = target.artifactId();
        renderPackRows();
        int generation = archiveGeneration.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                File file = packStore.installedFile(target);
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
                    archivePackId = target.artifactId();
                    openingPackId = "";
                    attachArchiveToWebView();
                    homeSearchInput.setEnabled(true);
                    renderPackRows();
                    messageBar.setVisibility(View.GONE);
                    if (currentScreen != requestScreen
                            || navigationGeneration != navigationAtRequest) {
                        return;
                    }
                    searchReturnScreen = returnScreen;
                    showSearch();
                    if (searchImmediately) {
                        performSearch();
                    }
                });
            } catch (Exception | LinkageError error) {
                postToUi(() -> {
                    if (generation != archiveGeneration.get()) {
                        return;
                    }
                    openingPackId = "";
                    homeSearchInput.setEnabled(true);
                    renderPackRows();
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
        KiwixArchive searchArchive = archive;
        String searchArchiveId = archivePackId;
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
                            && searchArchiveId.equals(archivePackId)) {
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
                        || !searchArchiveId.equals(archivePackId)) {
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
        navigationGeneration++;
        currentScreen = Screen.READER;
        updateKeepScreenOn();
        homeScreen.setVisibility(View.GONE);
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        libraryButton.setVisibility(View.VISIBLE);
        toolbarTitle.setText(result.title);
        clearHistoryOnPageFinish = true;
        articleWebView.loadUrl(archive.contentUrl(result.path));
    }

    private void showHome() {
        navigationGeneration++;
        currentScreen = Screen.HOME;
        updateKeepScreenOn();
        homeScreen.setVisibility(View.VISIBLE);
        libraryScreen.setVisibility(View.GONE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.GONE);
        libraryButton.setVisibility(View.VISIBLE);
        toolbarTitle.setText(R.string.app_name);
        homeSearchInput.setEnabled(true);
        homeScreen.requestFocus();
        hideKeyboard();
    }

    private void showLibrary() {
        navigationGeneration++;
        currentScreen = Screen.LIBRARY;
        updateKeepScreenOn();
        homeScreen.setVisibility(View.GONE);
        libraryScreen.setVisibility(View.VISIBLE);
        searchScreen.setVisibility(View.GONE);
        readerScreen.setVisibility(View.GONE);
        backButton.setVisibility(View.VISIBLE);
        libraryButton.setVisibility(View.GONE);
        toolbarTitle.setText(R.string.library_title);
        hideKeyboard();
        if (!packReconcilePending) {
            refreshLibraryState();
        }
        if (!packReconcilePending && !catalogRefreshAttempted) {
            refreshCatalog();
        }
    }

    private void showSearch() {
        navigationGeneration++;
        currentScreen = Screen.SEARCH;
        updateKeepScreenOn();
        homeScreen.setVisibility(View.GONE);
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
            if (searchReturnScreen == Screen.LIBRARY) {
                showLibrary();
            } else {
                showHome();
            }
        } else if (currentScreen == Screen.LIBRARY) {
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
        Window window = getWindow();
        boolean activeLibraryDownload = currentScreen == Screen.LIBRARY
                && (updateState == UpdateState.DOWNLOADING
                || (packDownloads != null && packDownloads.query().isActive()));
        if (currentScreen == Screen.READER || activeLibraryDownload) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void pageBy(int direction) {
        int overlap = Math.round(48 * getResources().getDisplayMetrics().density);
        int distance = Math.max(1, articleWebView.getHeight() - overlap);
        articleWebView.scrollBy(0, direction * distance);
    }

    private void pageLibraryBy(int direction) {
        int overlap = Math.round(48 * getResources().getDisplayMetrics().density);
        int distance = Math.max(1, offlinePackList.getHeight() - overlap);
        offlinePackList.scrollListBy(direction * distance);
    }

    private void adjustTextZoom(int delta) {
        textZoom = Math.max(MIN_TEXT_ZOOM, Math.min(MAX_TEXT_ZOOM, textZoom + delta));
        articleWebView.getSettings().setTextZoom(textZoom);
        getPreferences(MODE_PRIVATE).edit().putInt("reader_text_zoom", textZoom).apply();
        showMessage("正文缩放 " + textZoom + "%");
    }

    private void confirmRemovePack(OfflinePack target) {
        String consequence = "删除后需要重新下载才能使用这个搜索库。";
        if (selectedPack != null
                && target.artifactId().equals(selectedPack.artifactId())) {
            OfflinePack replacement = firstInstalledExcept(target);
            consequence = replacement == null
                    ? "删除后将没有可用的首页搜索库。"
                    : "删除后将自动改用《" + displayTitle(replacement) + "》。";
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_remove_title)
                .setMessage("将删除《" + displayTitle(target) + "》。" + consequence)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (ignored, which) -> removePack(target))
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(0);
        }
        dialog.show();
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

    private OfflinePack firstInstalledExcept(OfflinePack excluded) {
        List<OfflinePack> installed = new ArrayList<>();
        for (OfflinePack candidate : catalogPacks) {
            if (!candidate.artifactId().equals(excluded.artifactId())
                    && packStore.isInstalled(candidate)) {
                installed.add(candidate);
            }
        }
        installed.sort(Comparator.comparing(OfflinePack::artifactId));
        return installed.isEmpty() ? null : installed.get(0);
    }

    private void removePack(OfflinePack target) {
        showLibrary();
        deletingPackId = target.artifactId();
        KiwixArchive archiveToClose = detachArchiveForMutation(target);
        renderPackRows();
        PackTaskCoordinator.execute(() -> {
            if (archiveToClose != null) {
                archiveToClose.close();
            }
            try {
                boolean removed = packStore.deleteInstalled(target);
                if (removed) {
                    installedPackSnapshots.remove(target.artifactId());
                }
                postToUi(() -> {
                    deletingPackId = "";
                    if (removed) {
                        invalidPacks.remove(target.artifactId());
                        packFailures.remove(target.artifactId());
                        if (selectedPack != null
                                && target.artifactId().equals(selectedPack.artifactId())) {
                            selectedPack = null;
                            try {
                                packSelection.clearIfSelected(target);
                            } catch (IOException ignored) {
                                // restoreSelectedPack will ignore a selection without a file.
                            }
                        }
                        restoreSelectedPack();
                        renderPackRows();
                    } else {
                        renderPackRows();
                        showMessage("文件仍在使用，暂时无法删除");
                    }
                });
            } catch (IOException error) {
                postToUi(() -> {
                    deletingPackId = "";
                    renderPackRows();
                    showMessage(readableError(error));
                });
            }
        });
    }

    /** Detaches a native archive before its backing file can be deleted or replaced. */
    private KiwixArchive detachArchiveForMutation(OfflinePack target) {
        String artifactId = target.artifactId();
        boolean openingTarget = artifactId.equals(openingPackId);
        boolean openedTarget = artifactId.equals(archivePackId);
        if (!openingTarget && !openedTarget) {
            return null;
        }
        archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        if (openingTarget) {
            openingPackId = "";
            homeSearchInput.setEnabled(true);
        }
        if (!openedTarget) {
            return null;
        }
        articleWebView.stopLoading();
        articleWebView.setWebViewClient(new WebViewClient());
        articleWebView.loadUrl("about:blank");
        resultAdapter.replace(null);
        KiwixArchive detached = archive;
        archive = null;
        archivePackId = "";
        return detached;
    }

    private void invalidateArchiveSession() {
        archiveGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        openingPackId = "";
        homeSearchInput.setEnabled(true);
        articleWebView.stopLoading();
        articleWebView.setWebViewClient(new WebViewClient());
        articleWebView.loadUrl("about:blank");
        resultAdapter.replace(null);
        KiwixArchive toClose = archive;
        archive = null;
        archivePackId = "";
        if (toClose != null) {
            PackTaskCoordinator.execute(toClose::close);
        }
        renderPackRows();
    }

    private void beginPackReconciliation() {
        packReconcilePending = true;
        offlinePackList.setEnabled(false);
        refreshCatalogButton.setEnabled(false);
        PackTaskCoordinator.execute(() -> postToUi(() -> {
            packReconcilePending = false;
            offlinePackList.setEnabled(true);
            refreshCatalogButton.setEnabled(!catalogRefreshRunning);
            loadLocalPackCatalog();
            refreshLibraryState();
            scheduleDownloadPoll();
            if (currentScreen == Screen.LIBRARY && !catalogRefreshAttempted) {
                refreshCatalog();
            }
        }));
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
        archivePackId = "";
        if (toClose != null) {
            PackTaskCoordinator.execute(toClose::close);
        }
        ioExecutor.shutdown();
        super.onDestroy();
    }
}
