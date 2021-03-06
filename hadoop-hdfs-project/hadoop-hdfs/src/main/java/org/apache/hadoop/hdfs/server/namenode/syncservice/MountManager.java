/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.syncservice;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.hdfs.protocol.DisconnectPolicy;
import org.apache.hadoop.hdfs.protocol.MetadataSyncTaskOperation;
import org.apache.hadoop.hdfs.protocol.MountException;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport.DiffReportEntry;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport.DiffType;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport.INodeType;
import org.apache.hadoop.hdfs.protocol.SnapshottableDirectoryStatus;
import org.apache.hadoop.hdfs.protocol.SyncMount;
import org.apache.hadoop.hdfs.protocol.SyncTaskStats;
import org.apache.hadoop.hdfs.protocol.SyncTaskStats.Metrics;
import org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.SyncMountProto;
import org.apache.hadoop.hdfs.protocolPB.PBHelperClient;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.XAttrStorage;
import org.apache.hadoop.hdfs.server.namenode.snapshot.DirectorySnapshottableFeature;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotManager;
import org.apache.hadoop.hdfs.server.protocol.BlockSyncTaskExecutionFeedback;
import org.apache.hadoop.hdfs.server.protocol.BulkSyncTaskExecutionFeedback;
import org.apache.hadoop.hdfs.server.protocol.MetadataSyncTaskExecutionFeedback;
import org.apache.hadoop.hdfs.util.ReadOnlyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.hadoop.fs.XAttr.NameSpace.USER;
import static org.apache.hadoop.fs.XAttrSetFlag.CREATE;
import static org.apache.hadoop.fs.XAttrSetFlag.REPLACE;

/**
 * Interface for the MountManager. Used to create and remove backups.
 * MountManager relies on SnapshotManager to load snapshottable directories
 * from fsimage and edit logs. So when a snapshot is found in the underlying
 * fsimage/edit logs, the SnapshotManager can notify the MountManager about
 * them to.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class MountManager implements Configurable {
  public static final String PROVIDED_SYNC_PREVIOUS_FROM_SNAPSHOT_NAME =
      "PROVIDED_SYNC_PREVIOUS_FROM_SNAPSHOT_NAME";
  public static final String PROVIDED_SYNC_PREVIOUS_TO_SNAPSHOT_NAME =
      "PROVIDED_SYNC_PREVIOUS_TO_SNAPSHOT_NAME";
  public static final String PROVIDED_SYNC_MOUNT_DETAILS =
      "PROVIDED_SYNC_MOUNT_DETAILS";
  public static final String NO_FROM_SNAPSHOT_YET = "no_snapshot_yet";
  private static final Logger LOG = LoggerFactory.getLogger(MountManager.class);
  private FSNamesystem fsNamesystem;
  private Configuration conf;
  private Map<SyncMount, SyncTaskStats> syncMounts;

  public MountManager(FSNamesystem fsNamesystem) {
    this.fsNamesystem = fsNamesystem;
  }

  public void startService() {
    LOG.info("Loading SyncMounts from Snapshot dirs");
    this.syncMounts = findBackupDirsFromSnapshotDirs();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Loaded SyncMounts: {}", syncMounts);
    }
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  public String createBackup(Path localBackupDir, URI remoteBackupDir)
      throws MountException {
    String name = generateBackupName();
    return this.createBackup(new SyncMount(name, localBackupDir,
        remoteBackupDir));
  }

  public String createBackup(SyncMount syncMountToCreate)
      throws MountException {
    try {
      storeBackingUpSnapshotNameAsXAttr(syncMountToCreate.getLocalPath(),
          NO_FROM_SNAPSHOT_YET, NO_FROM_SNAPSHOT_YET, CREATE);
      setUpFileSystemForSnapshotting(syncMountToCreate);

    } catch (IOException e) {
      throw new MountException("Could not set up directory for snapshotting or create initial snapshot", e);
    }
    syncMounts.put(syncMountToCreate, SyncTaskStats.empty());

    LOG.info("Created {} successfully", syncMountToCreate);

    return syncMountToCreate.getName();
  }

  //TODO think this through. It does not seem like a good idea
  // to have they key change, but then where do we keep track of paused/resumed?
  public void pause(SyncMount syncMount) throws MountException {
    SyncTaskStats stats = syncMounts.remove(syncMount);
    if (stats != null) {
      syncMount.pause();
      syncMounts.put(syncMount, stats);
    }
  }

  public void resume(SyncMount syncMount) throws MountException {
    SyncTaskStats stats = syncMounts.remove(syncMount);
    if (stats != null) {
      syncMount.resume();
      syncMounts.put(syncMount, stats);
    }
  }

  public SyncTaskStats getStatistics(SyncMount syncMount)
      throws MountException {
    SyncTaskStats stat = syncMounts.get(syncMount);
    if (stat == null) {
      throw new MountException("SyncMount not found " + syncMount.getName());
    }
    return stat;
  }

  private void setUpFileSystemForSnapshotting(SyncMount syncMountToCreate)
      throws IOException {
    String localBackupPath =
        syncMountToCreate.getLocalPath().toString();
    try {
      setXattrForBackupMount(syncMountToCreate);
    } catch (IOException e) {
      LOG.error("Could not set XAttr on {}, unwinding allowSnapshot",
          localBackupPath);
      fsNamesystem.disallowSnapshot(localBackupPath);
      throw e;
    }
    fsNamesystem.allowSnapshot(localBackupPath);
    fsNamesystem.createSnapshot(localBackupPath, NO_FROM_SNAPSHOT_YET, true);
  }

  private void setXattrForBackupMount(SyncMount syncMount)
      throws IOException {
    XAttr nameXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName("PROVIDED_SYNC_MOUNT_NAME")
        .setValue(syncMount.getName().getBytes())
        .build();
    XAttr remoteBackupPathXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName("PROVIDED_SYNC_MOUNT_REMOTE_LOCATION")
        .setValue(syncMount.getRemoteLocation().toString().getBytes())
        .build();

    String mountPath = syncMount.getLocalPath().toString();

    setXAttrsOnMountPath(nameXattr, mountPath);

    setXAttrsOnMountPath(remoteBackupPathXattr, mountPath);

    storeBackupMountDetailsAsXAttr(syncMount);
  }

  private synchronized void setXAttrsOnMountPath(XAttr xAttr, String mountPath) throws IOException {
    EnumSet<XAttrSetFlag> localFlags = determineFlags(mountPath, xAttr);

    fsNamesystem.setXAttr(mountPath,
        xAttr,
        localFlags, false);
  }

  private EnumSet<XAttrSetFlag> determineFlags(String mountPath, XAttr nameXattr) throws IOException {
    List<XAttr> existingXAttrs = fsNamesystem.getXAttrs(
        mountPath,
        Lists.newArrayList());

    boolean mountAlreadyExists = existingXAttrs
        .stream()
        .anyMatch(xattr -> xattr.equalsIgnoreValue(nameXattr));

    return mountAlreadyExists ?
        EnumSet.of(REPLACE) :
        EnumSet.of(CREATE);
  }

  public String removeBackup(String name) throws MountException {
    return removeBackup(name, DisconnectPolicy.GRACEFULLY);
  }

  ;

  public String removeBackup(String name, DisconnectPolicy policy)
      throws MountException {
    SyncMount syncMount = getBackupMountByName(name);
    disconnect(syncMount, policy);
    syncMounts.remove(syncMount);
    return syncMount.getLocalPath().toString();
  }

  private void disconnect(SyncMount syncMount, DisconnectPolicy policy) {
    if (policy != DisconnectPolicy.GRACEFULLY) {
      throw new UnsupportedOperationException("TODO");
    }
  }

  public List<SyncMount> getSyncMounts() {
    return Collections.unmodifiableList(
        Lists.newArrayList(syncMounts.keySet()));
  }

  public SyncMount getSyncMount(String name) throws MountException {
    return getBackupMountByName(name);
  }

  /**
   * A unique backup name. e.g. sha-1 of uuid. Or a pairing of adjective + noun.
   *
   * @return Unique name identifying the backup
   */
  protected String generateBackupName() {
    return UUID.randomUUID().toString();
  }

  private SyncMount getBackupMountByName(String name) throws MountException {
    Optional<SyncMount> matchingBackupMount = this.syncMounts.keySet().stream()
        .filter(backupMount -> backupMount.getName().equals(name))
        .findFirst();
    return matchingBackupMount.orElseThrow(
        () -> MountException.nameDoesNotExistException(name));
  }

  /**
   * For backing up, an initial snapshot just lists the directory as being new,
   * since it's new to the backup system. However, the underlying snapshotting
   * infra doesn't work that way, so we construct the diff here.
   *
   * @param localBackupPath
   * @param snapshotName
   * @return
   */
  private SnapshotDiffReport performInitialDiff(Path localBackupPath,
      String snapshotName) {
    List<DiffReportEntry> entryList = Lists.newArrayList();
    DiffReportEntry entry = new DiffReportEntry(INodeType.DIRECTORY, DiffType.CREATE,
        ".".getBytes());
    entryList.add(entry);
    return new SnapshotDiffReport(
        localBackupPath.toString(), null, snapshotName, entryList);
  }

  public SnapshotDiffReport forceInitialSnapshot(Path localBackupPath) throws IOException {
    return makeSnapshotAndPerformDiffInternal(localBackupPath, NO_FROM_SNAPSHOT_YET);

  }

  public SnapshotDiffReport makeSnapshotAndPerformDiff(Path localBackupPath)
      throws IOException {
    String fromSnapshotName = getBackingUpPreviousToSnapshotName(localBackupPath);
    if (!NO_FROM_SNAPSHOT_YET.equals(fromSnapshotName)) {
      String snapshotName = getSnapshotNameFromXAttr(fromSnapshotName, localBackupPath);
      if (!snapshotName.endsWith("synced")) {
        return performPreviousDiff(localBackupPath);
      }
    }
    deleteBackingUpPreviousFromSnapshot(localBackupPath);
    return makeSnapshotAndPerformDiffInternal(localBackupPath, fromSnapshotName);
  }

  private void deleteBackingUpPreviousFromSnapshot(Path localBackupPath) throws IOException{
    String previousFromSnapshotName = getBackingUpPreviousFromSnapshotName(localBackupPath);
    if (NO_FROM_SNAPSHOT_YET.equals(previousFromSnapshotName)) {
      return;
    } else {
      previousFromSnapshotName += "-synced";
    }
    fsNamesystem.deleteSnapshot(localBackupPath.toString(), previousFromSnapshotName, true);
  }

  public SnapshotDiffReport makeSnapshotAndPerformDiffInternal(Path localBackupPath,
      String fromSnapshotName)
      throws IOException {

    String toSnapshotName = Snapshot.generateDefaultSnapshotName();
    storeBackingUpSnapshotNameAsXAttr(localBackupPath, fromSnapshotName, toSnapshotName, REPLACE);
    fsNamesystem.createSnapshot(localBackupPath.toString(), toSnapshotName,
            true);

    if (NO_FROM_SNAPSHOT_YET.equals(fromSnapshotName)) {
      //initial case
      return performInitialDiff(localBackupPath, toSnapshotName);
    } else {
      //Normal case
      fromSnapshotName = fromSnapshotName + "-synced";
      return fsNamesystem.getSnapshotDiffReport(
          localBackupPath.toString(), fromSnapshotName, toSnapshotName);

    }
  }

  public SnapshotDiffReport performPreviousDiff(Path localBackupPath) throws IOException {
    String fromSnapshotName = getBackingUpPreviousFromSnapshotName(localBackupPath);
    String toSnapshotName = getBackingUpPreviousToSnapshotName(localBackupPath);
    if (!NO_FROM_SNAPSHOT_YET.equals(fromSnapshotName)) {
      fromSnapshotName = fromSnapshotName + "-synced";
    }
    return fsNamesystem.getSnapshotDiffReport(localBackupPath.toString(), fromSnapshotName, toSnapshotName);
  }

  private void storeBackingUpSnapshotNameAsXAttr(Path localBackupPath, String fromSnapshotName,
          String toSnapshotName, XAttrSetFlag action) throws IOException {
    XAttr backupFromSnapshotNameXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName(PROVIDED_SYNC_PREVIOUS_FROM_SNAPSHOT_NAME)
        .setValue(fromSnapshotName.getBytes())
        .build();
    XAttr backupToSnapshotNameXattr = new XAttr.Builder()
            .setNameSpace(USER)
            .setName(PROVIDED_SYNC_PREVIOUS_TO_SNAPSHOT_NAME)
            .setValue(toSnapshotName.getBytes())
            .build();
    try {
      fsNamesystem.setXAttr(localBackupPath.toString(),
              backupFromSnapshotNameXattr,
              EnumSet.of(action), false);
      fsNamesystem.setXAttr(localBackupPath.toString(),
              backupToSnapshotNameXattr,
              EnumSet.of(action), false);
    } catch (IOException e) {
      LOG.error("Could not set XAttr on {}", localBackupPath.toString());
      throw e;
    }
  }

  /**
   * Use the SyncMountProto to write out the XAttr to detail the name and
   * the remote storage where the data will be placed.
   * FIXME: The SyncMountProto also contains a localPath which could
   * become incorrect if the backup directory is moved so it's not a bad idea to
   * make a new protobuf for this. When this is done, the
   * getBackingDetailsFromXAttr function will also need to be updated.
   *
   * @param mount
   */
  private void storeBackupMountDetailsAsXAttr(SyncMount mount) {
    SyncMountProto proto = PBHelperClient.convert(mount);
    XAttr backupFromSnapshotNameXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName(PROVIDED_SYNC_MOUNT_DETAILS)
        .setValue(proto.toByteArray())
        .build();

    try {
      fsNamesystem.setXAttr(mount.getLocalPath().toString(),
          backupFromSnapshotNameXattr,
          EnumSet.of(CREATE), false);
    } catch (IOException e) {
      LOG.error("Could not set XAttr PROVIDED_SYNC_MOUNT_DETAILS on {}",
          mount.getLocalPath());
    }
  }

  private String getBackingUpPreviousFromSnapshotName(Path localBackupPath)
      throws IOException {
    XAttr backupFromSnapshotNameXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName(PROVIDED_SYNC_PREVIOUS_FROM_SNAPSHOT_NAME)
        .build();
    List<XAttr> xAttrs = fsNamesystem.getXAttrs(localBackupPath.toString(),
        Lists.newArrayList(backupFromSnapshotNameXattr));
    return xAttrs.stream()
        .findFirst()
        .map(xAttr -> new String(xAttr.getValue()))
        .orElseThrow(() -> new MountException("Failed to get XAttr: " + PROVIDED_SYNC_PREVIOUS_FROM_SNAPSHOT_NAME));

  }

  private String getBackingUpPreviousToSnapshotName(Path localBackupPath)
          throws IOException {
    XAttr backupToSnapshotNameXattr = new XAttr.Builder()
            .setNameSpace(USER)
            .setName(PROVIDED_SYNC_PREVIOUS_TO_SNAPSHOT_NAME)
            .build();
    List<XAttr> xAttrs = fsNamesystem.getXAttrs(localBackupPath.toString(),
                                                Lists.newArrayList(backupToSnapshotNameXattr));
    return xAttrs.stream()
                 .findFirst()
                 .map(xAttr -> new String(xAttr.getValue()))
                 .orElseThrow(() -> new MountException("Failed to get toSnapshot from XArrt"));
  }

  public Optional<SyncMount> addPossibleLocalBackupDir(INode inode, int snapshotId) {
    List<XAttr> xAttrs = XAttrStorage.readINodeXAttrs(inode.getSnapshotINode(snapshotId));
    try {
      for (XAttr xAttr : xAttrs) {
        if (xAttr.getName().equals(PROVIDED_SYNC_MOUNT_DETAILS)) {
          SyncMountProto proto = SyncMountProto.parseFrom(xAttr.getValue());
          return Optional.of(PBHelperClient.convert(proto));
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      // Didn't find the appropriate values.
      return Optional.empty();
    }
  }


  private Optional<SyncMount> getBackingDetailsFromXAttr(Path localBackupPath) {
    XAttr backupFromSnapshotNameXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName(PROVIDED_SYNC_MOUNT_DETAILS)
        .build();
    try {
      List<XAttr> xAttrs = fsNamesystem.getXAttrs(localBackupPath.toString(),
          Lists.newArrayList(backupFromSnapshotNameXattr));
      for (XAttr xAttr : xAttrs) {
        if (xAttr.getName().equals(PROVIDED_SYNC_MOUNT_DETAILS)) {
          SyncMountProto proto = SyncMountProto.parseFrom(xAttr.getValue());
          return Optional.of(PBHelperClient.convert(proto));
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      // Didn't find the appropriate values.
      return Optional.empty();
    }
  }

  private void removeBackingDetailsFromXAttr(Path localBackupPath) {
    XAttr backupFromSnapshotNameXattr = new XAttr.Builder()
        .setNameSpace(USER)
        .setName(PROVIDED_SYNC_MOUNT_DETAILS)
        .build();
    try {
      fsNamesystem.removeXAttr(localBackupPath.toString(),
          backupFromSnapshotNameXattr, true);
    } catch (IOException e) {
      LOG.error("Could not remove XAttr for dir: {}", localBackupPath);
    }
  }

  private Map<SyncMount, SyncTaskStats> findBackupDirsFromSnapshotDirs() {
    Map<SyncMount, SyncTaskStats> mountsAndStats = Maps.newConcurrentMap();

    SnapshotManager snapshotManager = fsNamesystem.getSnapshotManager();
    SnapshottableDirectoryStatus[] snapshottableDirs =
        snapshotManager.getSnapshottableDirListing(null);
    if (snapshottableDirs == null) {
      return mountsAndStats;
    }

    for (SnapshottableDirectoryStatus dir : snapshottableDirs) {
      Path path = dir.getFullPath();
      getBackingDetailsFromXAttr(path).map(syncMount ->
          mountsAndStats.put(syncMount, SyncTaskStats.empty()));
    }
    return mountsAndStats;
  }

  public void addPossibleLocalBackupDir(String localDir) {
    Path path = new Path(localDir);
    getBackingDetailsFromXAttr(path).map(syncMount ->
        syncMounts.put(syncMount, SyncTaskStats.empty()));
  }

  public void removePossibleLocalBackupDir(String localDir) {
    Path path = new Path(localDir);
    syncMounts.entrySet().removeIf(
        m -> m.getKey().getLocalPath().equals(path));
  }

  public void updateStats(BulkSyncTaskExecutionFeedback
      bulkSyncTaskExecutionFeedback) {
    Collection<BlockSyncTaskExecutionFeedback> feedbacks =
        bulkSyncTaskExecutionFeedback.getFeedbacks();
    for (BlockSyncTaskExecutionFeedback feedback : feedbacks) {
      updateStats(feedback);
    }
  }

  public synchronized void updateStats(MetadataSyncTaskExecutionFeedback feedback) {
    String syncMountId = feedback.getSyncMountId();
    SyncMount key = findKey(syncMountId);
    SyncTaskStats stat = statify(feedback);
    syncMounts.merge(key, stat, SyncTaskStats::append);
  }

  public synchronized void updateStats(BlockSyncTaskExecutionFeedback feedback) {
    String syncMountId = feedback.getSyncMountId();
    SyncMount key = findKey(syncMountId);
    SyncTaskStats stat = statify(feedback);
    syncMounts.merge(key, stat, SyncTaskStats::append);
  }

  private SyncMount findKey(String syncMountId) {
    List<SyncMount> matchings = syncMounts
        .keySet()
        .stream()
        .filter(syncMount -> syncMount.getName().equals(syncMountId))
        .collect(Collectors.toList());

    if (matchings.size() == 1) {
      return matchings.get(0);
    } else {
      throw new IllegalArgumentException("SyncMountId not found in syncMounts");
    }
  }

  private SyncTaskStats statify(MetadataSyncTaskExecutionFeedback feedback) {
    return SyncTaskStats.from(feedback);
  }

  private SyncTaskStats statify(BlockSyncTaskExecutionFeedback feedback) {
    return SyncTaskStats.from(feedback);
  }

  public Integer getNumberOfSuccessfulMetaOps(SyncMount syncMount,
      MetadataSyncTaskOperation operation) {
    SyncTaskStats stat = syncMounts.getOrDefault(syncMount,
        SyncTaskStats.empty());
    return stat.getMetaSuccesses()
        .getOrDefault(operation, Metrics.of(0, 0L)).ops;
  }

  public Long getNumberOfBytesTransported(SyncMount syncMount) {
    SyncTaskStats stat = syncMounts.getOrDefault(syncMount,
        SyncTaskStats.empty());
    return stat.getBlockSuccesses().bytes;
  }

  public long getNumberOfSuccessfulBlockOps(SyncMount syncMount) {
    SyncTaskStats stat = syncMounts.getOrDefault(syncMount,
        SyncTaskStats.empty());
    return stat.getBlockSuccesses().ops;
  }

  public long getNumberOfFailedBlockOps(SyncMount syncMount) {
    SyncTaskStats stat = syncMounts.getOrDefault(syncMount,
        SyncTaskStats.empty());
    return stat.getBlockFailures();
  }

  public boolean isEmptyDiff(Path localPath) {
    try {
      String snapshotName = getBackingUpPreviousToSnapshotName(localPath);
      if (!NO_FROM_SNAPSHOT_YET.equals(snapshotName)) {
        snapshotName = snapshotName + "-synced";
      }
      SnapshotDiffReport diffReport = fsNamesystem
              .getSnapshotDiffReport(localPath.toString(), snapshotName, "");
      List<DiffReportEntry> diffList = diffReport.getDiffList();
      return diffList.isEmpty();
    } catch (IOException e) {
      LOG.error("Failed to get SnapshotDiffReport for: {}", localPath.toString(), e);
      return false;
    }
  }


  public void updateSyncMount(String syncMountId) throws IOException {
    SyncMount syncMount = getSyncMount(syncMountId);
    String snapshotName = getBackingUpPreviousToSnapshotName(syncMount.getLocalPath());
    String updatedSnapshotName = snapshotName + "-synced";
    fsNamesystem.renameSnapshot(syncMount.getLocalPath().toString(), snapshotName, updatedSnapshotName, false);
  }

  public List<SyncMount> getSyncMountForResync() {
    List<SyncMount> syncMountsToBeResync = new ArrayList<>();
    List<SyncMount> syncMounts = getSyncMounts();
    for (SyncMount syncMount : syncMounts) {
      try {
        String snapshotFromXAttr = getBackingUpPreviousToSnapshotName(syncMount.getLocalPath());
        String syncedFlag = snapshotFromXAttr + "-synced";
        Path localPath = syncMount.getLocalPath();
        INodeDirectory d = fsNamesystem.getFSDirectory().getINode(localPath.toString()).asDirectory();
        DirectorySnapshottableFeature sf = d.getDirectorySnapshottableFeature();
        if (sf == null) {
          continue;
        }
        ReadOnlyList<Snapshot> snapshotList = sf.getSnapshotList();
        for (Snapshot snapshot : snapshotList) {
          String snapshotName = Snapshot.getSnapshotName(snapshot);
          if (snapshotName.equals(syncedFlag)) {
            syncMountsToBeResync.add(syncMount);
            break;
          }
        }
      } catch (IOException e) {
        LOG.error("Failed to get sync status for {}, adding for resync", syncMount);
        syncMountsToBeResync.add(syncMount);
      }
    }
    return syncMountsToBeResync;
  }
  private String getSnapshotNameFromXAttr(String snapshotFromXAttr, Path dir) {
    try {
      INodeDirectory d = fsNamesystem.getFSDirectory().getINode(dir.toString()).asDirectory();
      DirectorySnapshottableFeature sf = d.getDirectorySnapshottableFeature();
      if (sf == null) {
        return "";
      }
      ReadOnlyList<Snapshot> snapshotList = sf.getSnapshotList();
      for (Snapshot snapshot : snapshotList) {
        String snapshotName = Snapshot.getSnapshotName(snapshot);
        if (snapshotName.startsWith(snapshotFromXAttr)) {
          return snapshotName;
        }
      }
      return "";
    } catch (IOException e) {
      return "";
    }
  }

}
