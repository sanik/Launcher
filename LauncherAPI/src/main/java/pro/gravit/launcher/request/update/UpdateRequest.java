package pro.gravit.launcher.request.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

import pro.gravit.launcher.Launcher;
import pro.gravit.launcher.LauncherAPI;
import pro.gravit.launcher.LauncherNetworkAPI;
import pro.gravit.launcher.downloader.ListDownloader;
import pro.gravit.launcher.events.request.UpdateRequestEvent;
import pro.gravit.launcher.hasher.FileNameMatcher;
import pro.gravit.launcher.hasher.HashedDir;
import pro.gravit.launcher.hasher.HashedEntry;
import pro.gravit.launcher.hasher.HashedFile;
import pro.gravit.launcher.request.Request;
import pro.gravit.launcher.request.update.UpdateRequest.State.Callback;
import pro.gravit.launcher.request.websockets.StandartClientWebSocketService;
import pro.gravit.launcher.request.websockets.WebSocketRequest;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.LogHelper;

public final class UpdateRequest extends Request<UpdateRequestEvent> implements WebSocketRequest {
    public interface UpdateController {
        void preUpdate(UpdateRequest request, UpdateRequestEvent e) throws IOException;

        void preDiff(UpdateRequest request, UpdateRequestEvent e) throws IOException;

        void postDiff(UpdateRequest request, UpdateRequestEvent e, HashedDir.Diff diff) throws IOException;

        void preDownload(UpdateRequest request, UpdateRequestEvent e, List<ListDownloader.DownloadTask> adds) throws IOException;

        void postDownload(UpdateRequest request, UpdateRequestEvent e) throws IOException;

        void postUpdate(UpdateRequest request, UpdateRequestEvent e) throws IOException;
    }

    private static UpdateController controller;

    public static void setController(UpdateController controller) {
        UpdateRequest.controller = controller;
    }
    public static UpdateController getController()
    {
        return controller;
    }

    @Override
    public String getType() {
        return "update";
    }

    public static final class State {
        @FunctionalInterface
        public interface Callback {
            @LauncherAPI
            void call(State state);
        }

        @LauncherAPI
        public final long fileDownloaded;
        @LauncherAPI
        public final long fileSize;
        @LauncherAPI
        public final long totalDownloaded;
        @LauncherAPI
        public final long totalSize;
        @LauncherAPI
        public final String filePath;

        @LauncherAPI
        public final Duration duration;

        public State(String filePath, long fileDownloaded, long fileSize, long totalDownloaded, long totalSize, Duration duration) {
            this.filePath = filePath;
            this.fileDownloaded = fileDownloaded;
            this.fileSize = fileSize;
            this.totalDownloaded = totalDownloaded;
            this.totalSize = totalSize;

            // Also store time of creation
            this.duration = duration;
        }

        @LauncherAPI
        public double getBps() {
            long seconds = duration.getSeconds();
            if (seconds == 0)
                return -1.0D; // Otherwise will throw /0 exception
            return totalDownloaded / (double) seconds;
        }

        @LauncherAPI
        public Duration getEstimatedTime() {
            double bps = getBps();
            if (bps <= 0.0D)
                return null; // Otherwise will throw /0 exception
            return Duration.ofSeconds((long) (getTotalRemaining() / bps));
        }

        @LauncherAPI
        public double getFileDownloadedKiB() {
            return fileDownloaded / 1024.0D;
        }

        @LauncherAPI
        public double getFileDownloadedMiB() {
            return getFileDownloadedKiB() / 1024.0D;
        }

        @LauncherAPI
        public double getFileDownloadedPart() {
            if (fileSize == 0)
                return 0.0D;
            return (double) fileDownloaded / fileSize;
        }

        @LauncherAPI
        public long getFileRemaining() {
            return fileSize - fileDownloaded;
        }

        @LauncherAPI
        public double getFileRemainingKiB() {
            return getFileRemaining() / 1024.0D;
        }

        @LauncherAPI
        public double getFileRemainingMiB() {
            return getFileRemainingKiB() / 1024.0D;
        }

        @LauncherAPI
        public double getFileSizeKiB() {
            return fileSize / 1024.0D;
        }

        @LauncherAPI
        public double getFileSizeMiB() {
            return getFileSizeKiB() / 1024.0D;
        }

        @LauncherAPI
        public double getTotalDownloadedKiB() {
            return totalDownloaded / 1024.0D;
        }

        @LauncherAPI
        public double getTotalDownloadedMiB() {
            return getTotalDownloadedKiB() / 1024.0D;
        }

        @LauncherAPI
        public double getTotalDownloadedPart() {
            if (totalSize == 0)
                return 0.0D;
            return (double) totalDownloaded / totalSize;
        }

        @LauncherAPI
        public long getTotalRemaining() {
            return totalSize - totalDownloaded;
        }

        @LauncherAPI
        public double getTotalRemainingKiB() {
            return getTotalRemaining() / 1024.0D;
        }

        @LauncherAPI
        public double getTotalRemainingMiB() {
            return getTotalRemainingKiB() / 1024.0D;
        }

        @LauncherAPI
        public double getTotalSizeKiB() {
            return totalSize / 1024.0D;
        }

        @LauncherAPI
        public double getTotalSizeMiB() {
            return getTotalSizeKiB() / 1024.0D;
        }
    }

    @Override
    public UpdateRequestEvent requestDo(StandartClientWebSocketService service) throws Exception {
        LogHelper.debug("Start update request");
        UpdateRequestEvent e = (UpdateRequestEvent) service.sendRequest(this);
        if (controller != null) controller.preUpdate(this, e);
        LogHelper.debug("Start update");
        Launcher.profile.pushOptionalFile(e.hdir, !Launcher.profile.isUpdateFastCheck());
        if (controller != null) controller.preDiff(this, e);
        HashedDir.Diff diff = e.hdir.diff(localDir, matcher);
        if (controller != null) controller.postDiff(this, e, diff);
        final List<ListDownloader.DownloadTask> adds = new ArrayList<>();
        if (controller != null) controller.preDownload(this, e, adds);
        diff.mismatch.walk(IOHelper.CROSS_SEPARATOR, (path, name, entry) -> {
            if (entry.getType().equals(HashedEntry.Type.FILE)) {
                if (!entry.flag) {
                    HashedFile file = (HashedFile) entry;
                    totalSize += file.size;
                    adds.add(new ListDownloader.DownloadTask(path, file.size));
                }
            } else if (entry.getType().equals(HashedEntry.Type.DIR)) {
                try {
                    Files.createDirectories(dir.resolve(path));
                } catch (IOException ex) {
                    LogHelper.error(ex);
                }
            }
            return HashedDir.WalkAction.CONTINUE;
        });
        totalSize = diff.mismatch.size();
        startTime = Instant.now();
        updateState("UnknownFile", 0L, 100);
        ListDownloader listDownloader = new ListDownloader();
        LogHelper.info("Download %s to %s", dirName, dir.toAbsolutePath().toString());
        if (e.zip && !adds.isEmpty()) {
            listDownloader.downloadZip(e.url, adds, dir, this::updateState, (add) -> totalDownloaded += add, e.fullDownload);
        } else {
            listDownloader.download(e.url, adds, dir, this::updateState, (add) -> totalDownloaded += add);
        }
        if (controller != null) controller.postDownload(this, e);
        deleteExtraDir(dir, diff.extra, diff.extra.flag);
        if (controller != null) controller.postUpdate(this, e);
        LogHelper.debug("Update success");
        return e;
    }

    // Instance
    @LauncherNetworkAPI
    private final String dirName;
    private transient final Path dir;

    public Path getDir() {
        return dir;
    }

    private transient final FileNameMatcher matcher;

    private transient final boolean digest;
    private transient volatile Callback stateCallback;
    // State
    private transient HashedDir localDir;
    private transient long totalDownloaded;

    private transient long totalSize;

    private transient Instant startTime;

    @LauncherAPI
    public UpdateRequest(String dirName, Path dir, FileNameMatcher matcher, boolean digest) {
        this.dirName = IOHelper.verifyFileName(dirName);
        this.dir = Objects.requireNonNull(dir, "dir");
        this.matcher = matcher;
        this.digest = digest;
    }

    private void deleteExtraDir(Path subDir, HashedDir subHDir, boolean flag) throws IOException {
        for (Entry<String, HashedEntry> mapEntry : subHDir.map().entrySet()) {
            String name = mapEntry.getKey();
            Path path = subDir.resolve(name);

            // Delete list and dirs based on type
            HashedEntry entry = mapEntry.getValue();
            HashedEntry.Type entryType = entry.getType();
            switch (entryType) {
                case FILE:
                    updateState(IOHelper.toString(path), 0, 0);
                    Files.delete(path);
                    break;
                case DIR:
                    deleteExtraDir(path, (HashedDir) entry, flag || entry.flag);
                    break;
                default:
                    throw new AssertionError("Unsupported hashed entry type: " + entryType.name());
            }
        }

        // Delete!
        if (flag) {
            updateState(IOHelper.toString(subDir), 0, 0);
            Files.delete(subDir);
        }
    }

    @Override
    public UpdateRequestEvent request() throws Exception {
        Files.createDirectories(dir);
        localDir = new HashedDir(dir, matcher, false, digest);

        // Start request
        return super.request();
    }

    @LauncherAPI
    public void setStateCallback(Callback callback) {
        stateCallback = callback;
    }

    private void updateState(String filePath, long fileDownloaded, long fileSize) {
        if (stateCallback != null)
            stateCallback.call(new State(filePath, fileDownloaded, fileSize,
                    totalDownloaded, totalSize, Duration.between(startTime, Instant.now())));
    }
}
