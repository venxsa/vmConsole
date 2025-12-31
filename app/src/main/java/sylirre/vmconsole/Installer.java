/*
*************************************************************************
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*************************************************************************
*/
package sylirre.vmconsole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.AssetManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Runtime data installer for assets embedded into APK.
 */
@SuppressWarnings("WeakerAccess")
public class Installer {

    /**
     * Performs installation of runtime data if necessary.
     */
    public static void setupIfNeeded(final Activity activity, final Runnable whenDone) {
        // List of files to extract.
        final String[] runtimeDataFiles = {
            "bios-256k.bin",
            "efi-virtio.rom",
            "kvmvapic.bin",
            Config.CDROM_IMAGE_NAME,
            Config.PRIMARY_HDD_IMAGE_NAME,
            Config.SECONDARY_HDD_IMAGE_NAME,
        };

        boolean allFilesPresent = true;
        for (String dataFile : runtimeDataFiles) {
            if (!new File(Config.getDataDirectory(activity), dataFile).exists()) {
                allFilesPresent = false;
                break;
            }
        }

        final TerminalPreferences prefs = new TerminalPreferences(activity);

        // If all files are present and application was not upgraded, no need to
        // extract files.
        if (allFilesPresent && BuildConfig.VERSION_CODE == prefs.getDataVersion()) {
            whenDone.run();
            return;
        }

        LayoutInflater inflater = activity.getLayoutInflater();
        View progressView = inflater.inflate(R.layout.installer_progress, null);
        final TextView progressTitle = progressView.findViewById(R.id.progress_title);
        final TextView progressSubtitle = progressView.findViewById(R.id.progress_subtitle);
        final TextView progressPercent = progressView.findViewById(R.id.progress_percent);
        final ProgressBar progressBar = progressView.findViewById(R.id.progress_bar);
        progressBar.setIndeterminate(true);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
        dialogBuilder.setCancelable(false);
        dialogBuilder.setView(progressView);

        final AlertDialog progress = dialogBuilder.create();
        progress.show();

        new Thread() {
            @Override
            public void run() {
                try {
                    AssetManager assetManager = activity.getAssets();
                    final byte[] buffer = new byte[16384];
                    for (String dataFile : runtimeDataFiles) {
                        File outputFile = new File(Config.getDataDirectory(activity), dataFile);

                        // We do not want to overwrite user data during upgrade.
                        if (dataFile.equals(Config.PRIMARY_HDD_IMAGE_NAME) && outputFile.exists()) {
                            continue;
                        }
                        if (dataFile.equals(Config.SECONDARY_HDD_IMAGE_NAME) && outputFile.exists()) {
                            continue;
                        }
                        if (dataFile.equals(Config.CDROM_IMAGE_NAME) && outputFile.exists() && outputFile.length() > 0) {
                            continue;
                        }

                        setStatusText(activity, progressTitle, activity.getString(R.string.installer_progress_copying, dataFile));
                        setStatusText(activity, progressSubtitle, activity.getString(R.string.installer_progress_detail));
                        activity.runOnUiThread(() -> {
                            progressBar.setIndeterminate(true);
                            progressBar.setProgress(0);
                            progressPercent.setText("");
                        });
                        Log.i(Config.INSTALLER_LOG_TAG, "extracting runtime data: " + dataFile);
                        try {
                            try (InputStream inStream = assetManager.open(dataFile)) {
                                try (FileOutputStream outStream = new FileOutputStream(outputFile)) {
                                    int readBytes;
                                    while ((readBytes = inStream.read(buffer)) != -1) {
                                        outStream.write(buffer, 0, readBytes);
                                    }
                                    outStream.flush();
                                }
                            }
                        } catch (IOException assetError) {
                            if (!dataFile.equals(Config.CDROM_IMAGE_NAME)) {
                                throw assetError;
                            }

                            // Try a bundled compressed ISO first to keep offline installs possible without
                            // inflating the APK with the raw image.
                            if (copyBundledIsoArchive(assetManager, outputFile, buffer, activity, progressBar, progressPercent, progressTitle, progressSubtitle)) {
                                continue;
                            }

                            setStatusText(activity, progressTitle, activity.getString(R.string.installer_progress_downloading));
                            setStatusText(activity, progressSubtitle, Config.CDROM_IMAGE_URL);
                            downloadFile(Config.CDROM_IMAGE_URL, outputFile, buffer, activity, progressBar, progressPercent);
                        }
                    }

                    // Need to register current data version, so we can track it and determine
                    // whether data files need to be extracted again.
                    prefs.updateDataVersion(activity);

                    activity.runOnUiThread(whenDone);
                } catch (final Exception e) {
                    Log.e(Config.INSTALLER_LOG_TAG, "runtime data installation failed", e);
                    activity.runOnUiThread(() -> {
                        try {
                            new AlertDialog.Builder(activity)
                                .setTitle(R.string.installer_error_title)
                                .setMessage(R.string.installer_error_body)
                                .setNegativeButton(R.string.exit_label, (dialog, which) -> {
                                    dialog.dismiss();
                                    activity.finish();
                                }).setPositiveButton(R.string.installer_error_try_again_button, (dialog, which) -> {
                                dialog.dismiss();
                                Installer.setupIfNeeded(activity, whenDone);
                            }).show();
                        } catch (WindowManager.BadTokenException e1) {
                            // Activity already dismissed - ignore.
                        }
                    });
                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    private static boolean copyBundledIsoArchive(final AssetManager assetManager, final File outputFile, final byte[] buffer,
                                                 final Activity activity, final ProgressBar progressBar,
                                                 final TextView progressPercent, final TextView progressTitle,
                                                 final TextView progressSubtitle) {
        try {
            setStatusText(activity, progressTitle, activity.getString(R.string.installer_progress_unpacking));
            setStatusText(activity, progressSubtitle, activity.getString(R.string.installer_progress_bundled_iso_detail, Config.CDROM_IMAGE_ARCHIVE_NAME));
            activity.runOnUiThread(() -> {
                progressBar.setIndeterminate(true);
                progressBar.setProgress(0);
                progressPercent.setText(activity.getString(R.string.installer_progress_bytes_unknown, formatBytes(0)));
            });

            try (InputStream assetStream = assetManager.open(Config.CDROM_IMAGE_ARCHIVE_NAME);
                 GZIPInputStream gzipInput = new GZIPInputStream(assetStream);
                 FileOutputStream outStream = new FileOutputStream(outputFile)) {
                int readBytes;
                long written = 0;
                while ((readBytes = gzipInput.read(buffer)) != -1) {
                    outStream.write(buffer, 0, readBytes);
                    written += readBytes;
                    final long writtenSnapshot = written;
                    activity.runOnUiThread(() -> progressPercent.setText(
                        activity.getString(R.string.installer_progress_bytes_unknown, formatBytes(writtenSnapshot))));
                }
                outStream.flush();
            }
            return true;
        } catch (IOException bundledError) {
            Log.w(Config.INSTALLER_LOG_TAG, "bundled ISO archive not present", bundledError);
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(Config.INSTALLER_LOG_TAG, "failed to remove incomplete bundled ISO output");
            }
            return false;
        }
    }

    private static void downloadFile(final String url, final File outputFile, final byte[] buffer,
                                     final Activity activity, final ProgressBar progressBar,
                                     final TextView progressPercent) throws IOException {
        Log.i(Config.INSTALLER_LOG_TAG, "downloading runtime data: " + url);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "vmConsole");
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("failed to download file: HTTP " + connection.getResponseCode());
        }

        final long contentLength = connection.getContentLengthLong();
        if (contentLength > 0) {
            activity.runOnUiThread(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setMax(100);
                progressPercent.setText(activity.getString(
                    R.string.installer_progress_bytes,
                    formatBytes(0),
                    formatBytes(contentLength),
                    0
                ));
            });
        } else {
            activity.runOnUiThread(() -> {
                progressBar.setIndeterminate(true);
                progressPercent.setText(activity.getString(R.string.installer_progress_bytes_unknown, formatBytes(0)));
            });
        }

        final File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            // Ensure the data directory is ready before writing the ISO.
            parentDir.mkdirs();
        }

        final File tempFile = File.createTempFile(outputFile.getName(), ".part", parentDir);
        try (InputStream inStream = connection.getInputStream();
             FileOutputStream outStream = new FileOutputStream(tempFile)) {
            int readBytes;
            long downloaded = 0;
            int lastPercent = 0;
            while ((readBytes = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, readBytes);
                downloaded += readBytes;
                if (contentLength > 0) {
                    final int percent = (int) ((downloaded * 100) / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final long downloadedSnapshot = downloaded;
                        activity.runOnUiThread(() -> {
                            progressBar.setProgress(percent);
                            progressPercent.setText(activity.getString(
                                R.string.installer_progress_bytes,
                                formatBytes(downloadedSnapshot),
                                formatBytes(contentLength),
                                percent
                            ));
                        });
                    }
                } else {
                    final String progressText = formatBytes(downloaded);
                    activity.runOnUiThread(() -> progressPercent.setText(
                        activity.getString(R.string.installer_progress_bytes_unknown, progressText)));
                }
            }
            outStream.flush();
            if (contentLength > 0 && downloaded != contentLength) {
                throw new IOException("download aborted: received " + downloaded + " of " + contentLength + " bytes");
            }

            if (outputFile.exists() && !outputFile.delete()) {
                throw new IOException("failed to replace existing file: " + outputFile.getAbsolutePath());
            }

            if (!tempFile.renameTo(outputFile)) {
                throw new IOException("failed to finalize download to target location");
            }
            if (contentLength > 0) {
                final int percent = 100;
                final long downloadedSnapshot = downloaded;
                activity.runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    progressPercent.setText(activity.getString(
                        R.string.installer_progress_bytes,
                        formatBytes(downloadedSnapshot),
                        formatBytes(contentLength),
                        percent
                    ));
                });
            }
        } finally {
            if (tempFile.exists() && !tempFile.equals(outputFile) && !tempFile.renameTo(outputFile)) {
                // Clean up incomplete temp files that weren't successfully promoted.
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            connection.disconnect();
        }
    }

    private static void setStatusText(final Activity activity, final TextView textView, final String text) {
        activity.runOnUiThread(() -> textView.setText(text));
    }

    private static String formatBytes(long bytes) {
        final String[] units = {"B", "KB", "MB", "GB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", value, units[unitIndex]);
    }
}
