package com.twitter.mesos.scheduler.storage.backup;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;
import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;

import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.Clock;
import com.twitter.mesos.codec.ThriftBinaryCodec;
import com.twitter.mesos.codec.ThriftBinaryCodec.CodingException;
import com.twitter.mesos.gen.storage.Snapshot;
import com.twitter.mesos.scheduler.storage.SnapshotStore;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A backup routine that layers over a snapshot store and periodically writes snapshots to
 * local disk.
 */
public interface StorageBackup {

  /**
   * Perform a storage backup immediately, blocking until it is complete.
   */
  void backupNow();

  class StorageBackupImpl implements StorageBackup, SnapshotStore<Snapshot> {
    private static final Logger LOG = Logger.getLogger(StorageBackup.class.getName());

    private static final String FILE_PREFIX = "scheduler-backup-";
    private final BackupConfig config;

    static class BackupConfig {
      final File dir;
      final int maxBackups;
      final Amount<Long, Time> interval;

      BackupConfig(File dir, int maxBackups, Amount<Long, Time> interval) {
        this.dir = checkNotNull(dir);
        this.maxBackups = maxBackups;
        this.interval = checkNotNull(interval);
      }
    }

    /**
     * Binding annotation that the underlying {@link SnapshotStore} must be bound with.
     */
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD}) @Retention(RUNTIME)
    @interface SnapshotDelegate { }

    private final SnapshotStore<Snapshot> delegate;
    private final Clock clock;
    private final long backupIntervalMs;
    private volatile long nextSnapshotMs;
    private final DateFormat backupDateFormat;

    @VisibleForTesting
    final AtomicLong successes = Stats.exportLong("scheduler_backup_success");
    @VisibleForTesting
    final AtomicLong failures = Stats.exportLong("scheduler_backup_failed");

    @Inject
    StorageBackupImpl(
        @SnapshotDelegate SnapshotStore<Snapshot> delegate,
        Clock clock,
        BackupConfig config) {

      this.delegate = checkNotNull(delegate);
      this.clock = checkNotNull(clock);
      this.config = checkNotNull(config);
      backupDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm");
      backupIntervalMs = config.interval.as(Time.MILLISECONDS);
      nextSnapshotMs = clock.nowMillis() + backupIntervalMs;
    }

    @Override public Snapshot createSnapshot() {
      Snapshot snapshot = delegate.createSnapshot();
      if (clock.nowMillis() >= nextSnapshotMs) {
        nextSnapshotMs += backupIntervalMs;
        save(snapshot);
      }

      return snapshot;
    }

    @Override public void backupNow() {
      nextSnapshotMs = clock.nowMillis();
      createSnapshot();
    }

    @VisibleForTesting
    String createBackupName() {
      return FILE_PREFIX + backupDateFormat.format(new Date(clock.nowMillis()));
    }

    private void save(Snapshot snapshot) {
      String backupName = createBackupName();
      String tempBackupName = "temp_" + backupName;
      File tempFile = new File(config.dir, tempBackupName);
      LOG.info("Saving backup to " + tempFile);
      try {
        byte[] backup = ThriftBinaryCodec.encodeNonNull(snapshot);
        Files.write(backup, tempFile);
        Files.move(tempFile, new File(config.dir, backupName));
        successes.incrementAndGet();
      } catch (IOException e) {
        failures.incrementAndGet();
        LOG.log(Level.SEVERE, "Failed to prepare backup " + backupName + ": " + e, e);
      } catch (CodingException e) {
        LOG.log(Level.SEVERE, "Failed to encode backup " + backupName + ": " + e, e);
        failures.incrementAndGet();
      } finally {
        if (tempFile.exists()) {
          LOG.info("Deleting incomplete backup file " + tempFile);
          tempFile.delete();
        }
      }

      File[] backups = config.dir.listFiles(BACKUP_FILTER);
      if (backups == null) {
        LOG.severe("Failed to list backup dir " + config.dir);
      } else {
        int backupsToDelete = backups.length - config.maxBackups;
        if (backupsToDelete > 0) {
          List<File> toDelete = Ordering.natural()
              .onResultOf(FILE_NAME)
              .sortedCopy(ImmutableList.copyOf(backups)).subList(0, backupsToDelete);
          LOG.info("Deleting " + backupsToDelete + " outdated backups: " + toDelete);
          for (File outdated : toDelete) {
            outdated.delete();
          }
        }
      }
    }

    private static final FilenameFilter BACKUP_FILTER = new FilenameFilter() {
      @Override public boolean accept(File file, String s) {
        return s.startsWith(FILE_PREFIX);
      }
    };

    @VisibleForTesting
    static final Function<File, String> FILE_NAME = new Function<File, String>() {
      @Override public String apply(File file) {
        return file.getName();
      }
    };

    @Override
    public void applySnapshot(Snapshot snapshot) {
      delegate.applySnapshot(snapshot);
    }
  }
}